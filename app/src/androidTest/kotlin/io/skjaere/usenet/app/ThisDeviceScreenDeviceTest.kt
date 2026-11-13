package io.skjaere.usenet.app

import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.HUB_FOOTNOTE
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.settings.ThisDeviceCard
import io.skjaere.usenet.app.settings.ThisDeviceDeps
import io.skjaere.usenet.app.settings.ThisDeviceScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.releases.AudioCodec
import io.skjaere.usenet.releases.Hdr
import io.skjaere.yenc.RapidYenc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Playback › This device on a phone: its row on the hub; with a remote, the focus starting on the Device card
 * once the facts are there and Down going card by card; the facts this device's own; at twice the default font size every
 * word whole and the last card reachable; for TalkBack each card's title a heading and each row read as one.
 */
@RunWith(AndroidJUnit4::class)
class ThisDeviceScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    private val card = SemanticsMatcher.keyIsDefined(ThisDeviceCard)
    private fun card(title: String) = SemanticsMatcher.expectValue(ThisDeviceCard, title)

    /** The card the remote's focus is on; null for none. */
    private fun focused(): String? = compose.onAllNodes(card and isFocused()).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(ThisDeviceCard)

    private fun press(key: Int): String? {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Every fact read: no "…" left. */
    private fun waitForTheFacts() {
        compose.waitUntil(5_000) { exists(hasText("rapidyenc")) && !exists(hasText("…")) }
    }

    /** Each row as TalkBack reads it, one merged node: its words, label first, by its label. */
    private fun rows(): Map<String, List<String>> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        .mapNotNull { node -> node.config[SemanticsProperties.Text].map { it.text }.takeIf { it.size >= 2 } }
        .associateBy { it.first() }

    @Test
    fun theHubsRowNamesTheModelAndTheAndroidVersionAndOpensThePage() {
        var opened = false
        compose.setContent { AppTheme { SettingsScreen({}, {}, {}, {}, {}, {}, onLogs = {}, onThisDevice = { opened = true }) } }
        val line = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        compose.onNode(hasText("This device") and hasText(line) and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
        assertTrue(exists(hasText(HUB_FOOTNOTE)))
    }

    /** In the remote's input mode the focus starts on Device once the facts are there; Down goes card by card to the last. */
    @Test
    fun inTheRemotesInputModeTheFocusStartsOnDeviceAndDownReachesEveryCard() {
        instrumentation.setInTouchMode(false)
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}) } }
        compose.waitUntil(5_000) { focused() == "Device" }
        assertFalse("the focus came before the facts", exists(hasText("…")))
        assertEquals(listOf("Display", "Video", "Audio", "yEnc decoding"), List(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) })
        // The last keeps it; Up goes back.
        assertEquals("yEnc decoding", press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Audio", press(KeyEvent.KEYCODE_DPAD_UP))
    }

    /**
     * The focus waits for the facts, as a card's rows change height while their "…" are filled in: with rapidyenc's read
     * held, the page shows "…" and no card takes the focus; once it is read, the focus goes to Device. The page's own
     * reads otherwise, the app's.
     */
    @Test
    fun inTheRemotesInputModeTheFocusWaitsForTheFacts() {
        instrumentation.setInTouchMode(false)
        val app = ThisDeviceDeps.of(context.container, context.applicationContext)
        val read = CompletableDeferred<Unit>()
        val held = ThisDeviceDeps(
            facts = { rate, tvLayouts, settled -> app.facts(rate, tvLayouts, settled).let { if (read.isCompleted) it else it.copy(native = null) } },
            readDisplay = app.readDisplay,
            probe = app.probe,
            memory = app.memory,
            native = {
                read.await()
                app.native()
            },
            displayChanges = app.displayChanges,
        )
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}, deps = held) } }
        // A row still being read, as TalkBack reads it: its label and "…".
        fun reading(label: String) = compose.onAllNodes(hasText(label) and hasText("…")).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(5_000) { !reading("Memory") && !reading("Decoded") && reading("rapidyenc") }
        // Every other read is done: long enough for a focus taken too soon to have been taken.
        repeat(10) {
            Thread.sleep(50)
            compose.waitForIdle()
            assertEquals("a card took the focus before the facts", null, focused())
        }
        assertTrue(reading("rapidyenc"))

        read.complete(Unit)
        compose.waitUntil(5_000) { focused() == "Device" }
        assertFalse(exists(hasText("…")))
    }

    /**
     * While the page shows, a change of the display is read: a Shield's TV says HDR10 without Dolby Vision for a few
     * seconds after it wakes, which the page mustn't keep. The display's facts and its changes are the test's; the rest
     * the app's.
     */
    @Test
    fun aChangeOfTheDisplayWhileThePageShowsIsRead() {
        val app = ThisDeviceDeps.of(context.container, context.applicationContext)
        var hdr = setOf(Hdr.Hdr10)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val deps = ThisDeviceDeps(
            facts = { rate, tvLayouts, settled -> app.facts(rate, tvLayouts, settled).let { it.copy(display = it.display?.copy(hdr = hdr)) } },
            readDisplay = app.readDisplay,
            probe = app.probe,
            memory = app.memory,
            native = app.native,
            displayChanges = changes,
        )
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}, deps = deps) } }
        waitForTheFacts()
        assertEquals(listOf("HDR", "HDR10"), rows()["HDR"])

        // The TV has woken: Dolby Vision is back.
        hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10)
        compose.runOnIdle {
            assertTrue("nothing follows the display while the page shows", changes.subscriptionCount.value > 0)
            changes.tryEmit(Unit)
        }
        try {
            compose.waitUntil(5_000) { rows()["HDR"] == listOf("HDR", "Dolby Vision", "HDR10") }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the display changed, the page still says ${rows()["HDR"]}", e)
        }
    }

    /**
     * While the page shows, a change of the HDMI audio route is read: a receiver switched on passes TrueHD through. The
     * route and its changes are the test's; the rest the app's. A phone shows Over HDMI only once something passes.
     */
    @Test
    fun aChangeOfTheAudioRouteWhileThePageShowsIsRead() {
        val app = ThisDeviceDeps.of(context.container, context.applicationContext)
        var passed = emptyList<AudioCodec>()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val deps = ThisDeviceDeps(
            facts = { rate, tvLayouts, settled ->
                app.facts(rate, tvLayouts, settled).let { it.copy(television = false, decoders = it.decoders?.copy(audioPassedThrough = passed)) }
            },
            readDisplay = app.readDisplay,
            probe = app.probe,
            memory = app.memory,
            native = app.native,
            displayChanges = app.displayChanges,
            audioChanges = changes,
        )
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}, deps = deps) } }
        waitForTheFacts()
        assertEquals(null, rows()["Over HDMI"])

        // A receiver switched on.
        passed = listOf(AudioCodec.Ac3, AudioCodec.Eac3, AudioCodec.TrueHd)
        compose.runOnIdle {
            assertTrue("nothing follows the audio route while the page shows", changes.subscriptionCount.value > 0)
            changes.tryEmit(Unit)
        }
        val now = listOf("Over HDMI", "DD, DD+, TrueHD", "passed through to the TV or receiver, as it is now: switching a receiver on or off updates this")
        try {
            compose.waitUntil(5_000) { rows()["Over HDMI"] == now }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the route changed, the page still says ${rows()["Over HDMI"]}", e)
        }
    }

    /** Touch: nothing takes the focus as the page opens, so no ring shows. */
    @Test
    fun inTouchModeNoCardIsFocused() {
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}) } }
        waitForTheFacts()
        compose.waitForIdle()
        assertEquals(null, focused())
    }

    /** What the page says is this device's, as the platform and the library say it, read apart from the page's model. */
    @Test
    fun thePageSaysThisDevice() {
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}) } }
        waitForTheFacts()
        val rows = rows()
        rows.values.forEach { Log.i(TAG, it.joinToString(" | ")) }
        assertEquals(listOf("Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()), rows["Model"])
        assertEquals(listOf("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"), rows["Android"])
        assertEquals(listOf("Kind", "Phone or tablet"), rows["Kind"])
        val abi = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first()
        assertEquals("${if (Process.is64Bit()) 64 else 32}-bit · $abi", rows["Process"]?.get(1))
        assertEquals(platformDisplayRow(context).first(), rows["Output"]?.get(1))
        assertEquals(listOf("rapidyenc", RapidYenc.version().toList().joinToString(".")), rows["rapidyenc"])
        listOf("Memory", "Play budget", "Largest mode", "Auto quality", "Refresh rates", "HDR", "Decoded", "Decode", "CRC32").forEach { label ->
            assertTrue("$label: ${rows[label]}", rows[label]?.get(1)?.let { it != "…" && it != "Can’t be read" } == true)
        }
        // A phone's: no Match frame rate.
        assertFalse(exists(hasText("Match frame rate")))
    }

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.singleOrNull()
    }

    /** At twice the default font size every word is whole (wrapping, never cut), and the last card and the footnote can be scrolled to. */
    @Test
    fun at200PercentEveryWordIsWholeAndTheLastCardReachable() {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) { AppTheme { Box(Modifier.testTag("screen")) { ThisDeviceScreen(onBack = {}) } } }
        }
        waitForTheFacts()
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        val cut = texts.mapNotNull { node ->
            val layout = node.layout() ?: return@mapNotNull null
            node.config[SemanticsProperties.Text].joinToString { it.text }
                .takeIf { layout.didOverflowHeight || layout.size.height < layout.multiParagraph.height - 1 || layout.isLineEllipsized(layout.lineCount - 1) }
        }
        assertEquals(emptyList<String>(), cut)
        val screen = compose.onNodeWithTag("screen").getBoundsInRoot()
        val last = compose.onNode(card("yEnc decoding"), useUnmergedTree = true).performScrollTo().assertIsDisplayed().getBoundsInRoot()
        assertTrue("the last card ($last) can't be scrolled onto the screen ($screen)", last.top >= screen.top - 1.dp && last.bottom <= screen.bottom + 1.dp)
        compose.onNode(hasText("The same facts go into the log each time the app starts."), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /** TalkBack: each card's title a heading, each row one item read as label, value, note. */
    @Test
    fun forTalkBackTheTitlesAreHeadingsAndEachRowIsReadAsOne() {
        compose.setContent { AppTheme { ThisDeviceScreen(onBack = {}) } }
        waitForTheFacts()
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(listOf("DEVICE", "DISPLAY", "VIDEO", "AUDIO", "YENC DECODING"), headings)
        val rows = rows()
        // A row with a note is still one item: its label, its value, its note.
        listOf("Memory", "Play budget", "Refresh rates").forEach { label -> assertEquals("$label: ${rows[label]}", 3, rows[label]?.size) }
        assertEquals("the screen’s own rates", rows["Refresh rates"]?.last())
    }

    private companion object {
        const val TAG = "ThisDevicePage"
    }
}

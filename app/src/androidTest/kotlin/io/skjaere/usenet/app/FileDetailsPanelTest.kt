package io.skjaere.usenet.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.FileDetails
import io.skjaere.usenet.app.play.FileDetailsPanel
import io.skjaere.usenet.app.play.FileDetailsSection
import io.skjaere.usenet.app.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * File details on a phone ([FileDetailsPanel]): at twice the default font size every word whole (wrapping, never cut),
 * the panel no wider than 400 dp and its last section reachable by scrolling, on the screen and in a landscape phone
 * 360 dp tall; for TalkBack a pane titled "File details" whose sections are headings and whose rows each read as
 * "label, value, note"; and until the tracks are known, the video and the device said to be read.
 */
@RunWith(AndroidJUnit4::class)
class FileDetailsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(details: FileDetails = ShieldFile.details, fontScale: Float = 1f, phone: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    if (phone) {
                        Box(Modifier.size(640.dp, 360.dp).testTag("screen")) { FileDetailsPanel(details, onClose = {}, safeArea = WindowInsets(0)) }
                    } else {
                        Box(Modifier.testTag("screen")) { FileDetailsPanel(details, onClose = {}, safeArea = WindowInsets(0)) }
                    }
                }
            }
        }
    }

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.singleOrNull()
    }

    /** Every text of the panel, by its words, and whether it is cut: clipped short of its lines, or ended in an ellipsis. */
    private fun cut(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        .mapNotNull { node ->
            val layout = node.layout() ?: return@mapNotNull null
            val words = node.config[SemanticsProperties.Text].joinToString { it.text }
            words.takeIf { layout.didOverflowHeight || layout.size.height < layout.multiParagraph.height - 1 || layout.isLineEllipsized(layout.lineCount - 1) }
        }

    private fun texts(): List<SemanticsNode> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()

    private fun DpRect.holds(other: DpRect) = other.left >= left - 1.dp && other.top >= top - 1.dp && other.right <= right + 1.dp && other.bottom <= bottom + 1.dp

    private fun checkWholeAndReachable() {
        assertEquals(emptyList<String>(), cut())
        val pane = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "File details")).getBoundsInRoot()
        assertTrue("the panel is wider than 400 dp: $pane", pane.right - pane.left <= 400.dp)
        val shown = texts().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        listOf(ShieldFile.RELEASE, ShieldFile.FILE, "the file’s size over its length", "Dolby Vision profile 8.1, level 6 · HDR10 base layer", "The name says HDR10+; the player can't confirm it")
            .forEach { words -> assertTrue("\"$words\" isn't shown whole: $shown", words in shown) }
        // The last section's last row, scrolled to, on the screen.
        val screen = compose.onNodeWithTag("screen").getBoundsInRoot()
        val providers = compose.onNode(hasText("Providers")).performScrollTo().assertIsDisplayed().getBoundsInRoot()
        assertTrue("the Providers row ($providers) can't be scrolled onto the screen ($screen)", screen.holds(providers))
    }

    @Test
    fun at200PercentEveryWordIsWholeAndTheLastSectionReachable() {
        show(fontScale = 2f)
        checkWholeAndReachable()
    }

    @Test
    fun at200PercentOnALandscapePhone360dpTallEveryWordIsWholeAndTheLastSectionReachable() {
        show(fontScale = 2f, phone = true)
        checkWholeAndReachable()
    }

    @Test
    fun forTalkBackItIsAPaneWhoseSectionsAreHeadingsAndEachRowReadsLabelValueNote() {
        show()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "File details")).assertIsDisplayed()
        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(listOf("File details") + ShieldFile.sections.map { it.uppercase() }, headings)
        // Each row one node for TalkBack: its label, its value (the HDR row's chips), its notes.
        val rows = ShieldFile.details.sections.flatMap { it.rows }
        assertEquals(14, rows.size)
        rows.forEach { row ->
            val read = compose.onNode(hasText(row.label)).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
            val expected = listOf(row.label) + row.hdr.map { it.label }.ifEmpty { listOf(row.value) } + listOfNotNull(row.note) + row.nameSays
            assertEquals(expected, read)
        }
        assertEquals(
            listOf("HDR", "Dolby Vision", "HDR10", "Dolby Vision profile 8.1, level 6 · HDR10 base layer", "The name says HDR10+; the player can't confirm it"),
            compose.onNode(hasText("HDR")).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
        )
    }

    /**
     * A section that drops out as the facts come (the Video section of a file whose video the player doesn't read, once the
     * tracks are known) leaves the focus on the section it was on, for a remote reading past it.
     */
    @Test
    fun aSectionThatDropsOutLeavesTheFocusWhereItWas() {
        var details by mutableStateOf(ShieldFile.reading)
        compose.setContent {
            AppTheme { Box { FileDetailsPanel(details, onClose = {}, safeArea = WindowInsets(0)) } }
        }
        val source = SemanticsMatcher.expectValue(FileDetailsSection, "Source")
        compose.onNode(source).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(source).assertIsFocused()
        details = FileDetails(ShieldFile.origin, FileDetails.Playing(tracksKnown = true, display = ShieldFile.playing.display))
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(SemanticsMatcher.expectValue(FileDetailsSection, "Video")).fetchSemanticsNodes().isEmpty())
        compose.onNode(source).assertIsFocused()
    }

    @Test
    fun beforeTheTracksAreKnownTheVideoAndTheDeviceAreSaidToBeRead() {
        show(ShieldFile.reading)
        val reading = compose.onAllNodes(hasText("Reading the file…"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, reading.size)
        compose.onNode(hasText("Container"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Frame rate"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Decoder"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}

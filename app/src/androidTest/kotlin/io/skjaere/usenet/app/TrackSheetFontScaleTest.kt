package io.skjaere.usenet.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.TrackSheet
import io.skjaere.usenet.app.play.TrackSheetPanel
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The audio and subtitles sheet at twice the default font size: every row at least a 48 dp touch
 * target with its words whole (wrapping, never cut), the panel no taller than the screen and no
 * wider than 400 dp, and its last row reachable by scrolling.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class TrackSheetFontScaleTest {
    @get:Rule
    val compose = createComposeRule()

    private fun audio(id: String, language: String, channels: Int, label: String? = null) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setChannelCount(channels).setLabel(label).build()

    private fun text(id: String, language: String, label: String? = null, flags: Int = 0) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language).setLabel(label).setSelectionFlags(flags).build()

    private val formats = listOf(
        audio("1", "en", 2), audio("2", "de", 6), audio("3", "en", 2, "Director's commentary with the cast"),
        Format.Builder().setId("4").setSampleMimeType(MimeTypes.AUDIO_E_AC3).setLanguage("fr").setChannelCount(6).build(),
        text("5", "en"), text("6", "en", "SDH"), text("7", "de", flags = C.SELECTION_FLAG_FORCED), text("8", "de"),
    )

    private fun show(fontScale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        val tracks = Tracks(
            formats.mapIndexed { i, f ->
                Tracks.Group(TrackGroup("0:$i", f), false, intArrayOf(if (f.id == "4") C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED), booleanArrayOf(f.id == "1"))
            },
        )
        val sheet = TrackSheet(TrackOptions.of(tracks, Locale.ENGLISH), SubtitlePlan.ForcedOnly, canReset = true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme { TrackSheetPanel(sheet, onAction = {}, onClose = {}, safeArea = WindowInsets(0)) }
            }
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private val rows = SemanticsMatcher("a row") { it.config.contains(SemanticsProperties.Role) && it.config.contains(SemanticsActions.OnClick) }

    @Test
    fun at200PercentEveryRowIsWholeAndReachable() {
        show(2f)
        val screen = compose.onRoot().getBoundsInRoot()
        val all = compose.onAllNodes(rows).fetchSemanticsNodes()
        // Four audio tracks, Off, Forced only, four subtitle tracks, Reset.
        assertTrue("rows: ${all.size}", all.size == 11)
        all.indices.forEach { i ->
            val row = compose.onAllNodes(rows)[i]
            row.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = row.getBoundsInRoot()
            assertTrue("row $i ($bounds) leaves the screen ($screen)", bounds.top >= screen.top && bounds.bottom <= screen.bottom)
            assertTrue("row $i is wider than the panel: $bounds", bounds.right - bounds.left <= 400.dp)
        }
        listOf("English · Stereo · AAC", "Director's commentary with the cast", "Can't play on this device", "Only parts in another language", "Forget this show's audio and subtitles")
            .forEach { words ->
                val node = compose.onAllNodesWithText(words, substring = true, useUnmergedTree = true)[0]
                node.performScrollTo()
                val layout = node.textLayout()
                assertFalse("'$words' is cut", layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1))
            }
        compose.onNodeWithText("Reset to my defaults", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}

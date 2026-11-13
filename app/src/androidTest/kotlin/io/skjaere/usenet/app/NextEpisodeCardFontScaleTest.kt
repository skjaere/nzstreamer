package io.skjaere.usenet.app

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.NextEpisodeCard
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.newznab.TvIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The next-episode card at Android's larger font sizes (nonlinear from Android 14, as the system
 * applies them): its buttons grow with their one-line labels instead of clipping them, and the
 * release line wraps instead of losing its end.
 */
@RunWith(AndroidJUnit4::class)
class NextEpisodeCardFontScaleTest {
    @get:Rule
    val compose = createComposeRule()

    private val next = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 900001),
        season = 2, episode = 6, episodeName = "Breakwater", airDate = null, runtimeMinutes = 45,
    )

    /**
     * A release of [sizeBytes]: 12.4 GB makes the release line about as wide as its column at the
     * default size. [prefetch] in its place shows the line for another state of the search.
     */
    private fun show(
        fontScale: Float,
        compact: Boolean = false,
        sizeBytes: Long = 12_400_000_000,
        prefetch: Prefetch? = Prefetch.Ready("Harbor.Lights.S02E06.1080p.WEB-DL", sizeBytes),
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        val card = AutoAdvance.Card(secondsLeft = 8, progress = 0.8f, prefetch = prefetch)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme { NextEpisodeCard(next, card, showStill = false, compact = compact, takeFocus = true, onStop = {}, onPlayNow = {}) }
            }
        }
    }

    /**
     * Lines beyond maxLines (a one-line label that would wrap) or the height the text was given.
     * (Not hasVisualOverflow: a simple Text reports its paragraph laid out at the full width it may
     * take, wider than the node.)
     */
    private fun TextLayoutResult.cut() = didOverflowHeight || isLineEllipsized(lineCount - 1)

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** Each button is a 44 dp touch target or more, showing its label on one line, whole and inside it. */
    private fun assertButtonsShowTheirLabels() {
        listOf("Stop", "Play now").forEach { label ->
            val button = compose.onNode(hasClickAction() and hasText(label)).assertHeightIsAtLeast(44.dp)
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertFalse("$label is cut or wraps", text.textLayout().cut())
            val outer = button.getBoundsInRoot()
            val inner = text.getBoundsInRoot()
            assertTrue("$label ($inner) sticks out of its button ($outer)", inner.top >= outer.top && inner.bottom <= outer.bottom && inner.left >= outer.left && inner.right <= outer.right)
        }
    }

    private fun assertTheReleaseLineIsWhole(text: String = "Release found") {
        val layout = compose.onNodeWithText(text, substring = true, useUnmergedTree = true).textLayout()
        assertFalse("the release line is cut", layout.cut())
    }

    @Test
    fun at150Percent() {
        show(1.5f)
        assertButtonsShowTheirLabels()
        assertTheReleaseLineIsWhole()
    }

    @Test
    fun at200Percent() {
        show(2f)
        assertButtonsShowTheirLabels()
        assertTheReleaseLineIsWhole()
    }

    /** The line for a search still to come (none running, and what was found no longer held) wraps whole too. */
    @Test
    fun at200PercentTheLineForASearchToComeIsWhole() {
        show(2f, prefetch = null)
        assertTheReleaseLineIsWhole("Searches again on play")
    }

    @Test
    fun compactAt200Percent() {
        show(2f, compact = true)
        assertButtonsShowTheirLabels()
    }

    /** At the default size the mockup's release line fits on one line. */
    @Test
    fun at100PercentTheReleaseLineIsOneLine() {
        show(1f, sizeBytes = 2_200_000_000)
        assertButtonsShowTheirLabels()
        assertEquals(1, compose.onNodeWithText("Release found", substring = true, useUnmergedTree = true).textLayout().lineCount)
    }
}

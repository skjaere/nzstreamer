package io.skjaere.usenet.app

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastPlaying
import io.skjaere.usenet.app.casting.CastPlayingDeps
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.MissingParts
import io.skjaere.usenet.app.play.MissingPartsPrompt
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's missing-parts prompt where PlayerActivity puts it (bottom end, 16 dp from the side and 100 dp from the
 * bottom) on a landscape phone whose short side is 360 dp. At twice the font size (QlPhoneLargeText) its words and buttons
 * are more than the 260 dp it has: Keep watching and Try another release still show whole, one under the other, and the
 * words scroll. TalkBack hears it once as it appears, a polite live region, the count in words (QlPhoneTalkBack).
 */
@RunWith(AndroidJUnit4::class)
class MissingPartsPromptLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun show(fontScale: Float, prompt: MissingParts.Prompt = MissingParts.Prompt(3, tryAnother = true)) {
        val context = instrumentation.targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    Box(Modifier.size(640.dp, 360.dp).testTag("screen")) {
                        MissingPartsPrompt(prompt, compact = false, takeFocus = false, onKeepWatching = {}, onTryAnother = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 100.dp))
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.cut() = didOverflowHeight || isLineEllipsized(lineCount - 1)

    /** Where the prompt may be: above the 100 dp the player keeps clear at the bottom. */
    private fun slot(): DpRect = compose.onNodeWithTag("screen").getBoundsInRoot().let { it.copy(bottom = it.bottom - 100.dp) }

    private fun DpRect.holds(other: DpRect) = other.top >= top - 1.dp && other.bottom <= bottom + 1.dp

    @Test
    fun at200PercentOnA360dpTallScreenBothButtonsShowWholeAndTheWordsScroll() {
        show(2f)
        val slot = slot()
        val bounds = listOf("Keep watching", "Try another release").map { label ->
            val button = compose.onNode(hasClickAction() and hasText(label)).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            val outer = button.getBoundsInRoot()
            assertTrue("$label ($outer) is outside the prompt's room ($slot)", slot.holds(outer))
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertFalse("$label is cut or wraps", text.textLayout().cut())
            outer
        }
        // One under the other, as they don't fit side by side.
        assertTrue("$bounds", bounds[1].top >= bounds[0].bottom)
        val question = compose.onNodeWithText("Try another release? It starts where you are.", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertFalse("the question is cut", question.textLayout().cut())
        assertTrue("the question can't be scrolled into the prompt's room", slot.holds(question.getBoundsInRoot()))
    }

    /**
     * A cast's prompt above the bar on the app's other screens (W37; CsPhoneMissingParts 2), on a phone turned to landscape
     * (780 x 360 dp, laid out past this portrait emulator's own width) at twice the font size: it takes no more than its
     * share of the window, its words scrolling and its buttons whole, so the page above it keeps room, and the Playing on
     * bar and the tabs under it theirs. Over a cast play followed from an invented feed: three missing parts within ten
     * minutes of film, another release to try.
     */
    @Test
    fun aCastsPromptAboveTheBarAt200PercentInLandscapeLeavesThePageTheBarAndTheTabsTheirRoom() {
        val context = instrumentation.targetContext
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val kitchen = CastTo("layout-test-route", "Kitchen display")
        val launch = PlayerLaunch(
            UsenetUris.build("layout-test-nzb", 0, "harbor-lights.mkv", CAST_SIZE), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(991_699, 2, 4),
            WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = 991_699, season = 2, episode = 4),
            SavedRelease("layout-test", "harbor-lights", "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-CAST", null, 0, "layout-test-nzb", "harbor-lights.mkv", CAST_SIZE),
            cast = kitchen,
        )
        // A second of missing articles each, by the file's share of the film: a thousand bytes a millisecond.
        val gaps = listOf(1_950_000L, 2_000_000L, 2_400_000L).map { at -> at * 1_000 until (at + 1_000) * 1_000 }
        val feed = MutableStateFlow<CastFeed?>(CastFeed("layout-test-load", launch, CastPlayerState.Playing, 1_900_000, CAST_LENGTH, size = CAST_SIZE, gaps = gaps, passed = gaps))
        val link = MutableStateFlow<CastLinkNow?>(CastLinkNow(kitchen.route, kitchen.name, CastLink.Connected))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cast = CastPlaying(
                scope,
                CastPlayingDeps(feed, link, pause = {}, resume = {}, seekTo = {}, stop = {}, clock = SystemClock::elapsedRealtime, canTryAnother = { true }),
            )
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                    AppTheme {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(780.dp, 360.dp).testTag("screen")) {
                            val nav = rememberNavController()
                            PhoneShell(nav, tabsFor(FormFactor.Phone), context.container.playback, playing = cast, cast = cast) {
                                NavHost(nav, startDestination = HomeRoute) { composable<HomeRoute> { Box(Modifier.fillMaxSize().testTag("page")) } }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasClickAction() and hasText("Try another release")).fetchSemanticsNodes().isNotEmpty() }
            val screen = compose.onNodeWithTag("screen").getBoundsInRoot()
            fun inside(what: String, bounds: DpRect) = assertTrue("$what ($bounds) is outside the screen ($screen)", bounds.top >= screen.top - 1.dp && bounds.bottom <= screen.bottom + 1.dp)
            // The page, room to use; the Playing on bar's button and the tabs whole under the prompt.
            val page = compose.onNodeWithTag("page").getBoundsInRoot()
            assertTrue("the page has ${page.height} left", page.height >= 48.dp)
            val home = compose.onNode(hasText("Home") and hasClickAction()).getBoundsInRoot()
            inside("the Home tab", home)
            assertTrue("the tabs squeezed: ${home.height}", home.height >= 79.dp)
            inside("the bar's Pause", compose.onNode(hasContentDescription("Pause on Kitchen display") and hasClickAction()).assertHeightIsAtLeast(44.dp).getBoundsInRoot())
            // The prompt's buttons whole, and its words there to scroll to.
            listOf("Keep watching", "Try another release").forEach { label ->
                inside(label, compose.onNode(hasClickAction() and hasText(label)).assertHeightIsAtLeast(44.dp).getBoundsInRoot())
                assertFalse("$label is cut or wraps", compose.onNodeWithText(label, useUnmergedTree = true).textLayout().cut())
            }
            val question = compose.onNodeWithText("Try another release? It starts where you are.", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            inside("the question", question.getBoundsInRoot())
        } finally {
            scope.cancel()
        }
    }

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    /** "This release is missing parts, 3 so far": the count in words, in one polite live region, the question and the buttons with it. */
    @Test
    fun talkBackHearsTheCountInWordsOnceAsItAppears() {
        show(1f)
        compose.onNode(hasContentDescription("This release is missing parts, 3 so far"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(polite, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** With no other release to offer, the count alone, likewise, and nothing to press. */
    @Test
    fun talkBackHearsTheCountAloneWithNoOtherRelease() {
        show(1f, MissingParts.Prompt(6, tryAnother = false))
        compose.onNode(hasContentDescription("This release is missing parts, 6 so far") and polite, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    private companion object {
        /** The cast's invented film: 52 minutes, in a file of a thousand bytes a millisecond. */
        const val CAST_LENGTH = 3_120_000L
        const val CAST_SIZE = 3_120_000_000L
    }
}

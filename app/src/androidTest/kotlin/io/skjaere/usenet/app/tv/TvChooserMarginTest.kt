package io.skjaere.usenet.app.tv

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.play.FileChoice
import io.skjaere.usenet.app.play.PlaySheet
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.StartedFrom
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.TvSafeArea
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The play sheet's file chooser on a real TV (tv36, PtTvIncoming 3): a list taller than the screen, as a season pack's
 * is, starts at the top of the TV's safe area, 27 dp down, not at its top edge; its rows scroll inside it, down to the
 * safe area's bottom. The release and its files are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvChooserMarginTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private val screenDp = instrumentation.targetContext.resources.displayMetrics.heightPixels / density

    @Before
    fun tv() {
        assumeTv()
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @Test
    fun aLongListsSheetStarts27DpFromTheTop() {
        val files = (1..24).map { FileChoice(it, "Harbor Lights - Episode $it.mkv", 4_400_000_000, sample = false) }
        val choosing = PlayState.ChoosingFile(
            "Harbor Lights", "S02E04 · Low Water", "Looking for S02E04 'Low Water'", "Harbor.Lights.S02.1080p.BluRay.DDP5.1.x264-GRP", files,
        )
        compose.setContent {
            AppTheme(FormFactor.Tv) {
                PlaySheet(choosing, onMinimize = {}, onCancel = {}, onDismiss = {}, onSearch = {}, onProviders = {}, startedFrom = StartedFrom("Galaxy phone"))
            }
        }
        val pane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Choose the file")
        compose.waitUntil(5_000) { compose.onAllNodes(pane).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val bounds = compose.onNode(pane).fetchSemanticsNode().boundsInWindow
        val top = bounds.top / density
        val bottom = bounds.bottom / density
        assertEquals("the sheet starts at the top of the TV's safe area: at $top dp of $screenDp", TvSafeArea.vertical.value, top, 0.6f)
        // Taller than the room left, its rows scroll down to the safe area's bottom, where the sheet keeps its own margin.
        assertEquals("its rows end at the safe area's bottom: at $bottom dp of $screenDp", screenDp - TvSafeArea.vertical.value, bottom, 0.6f)
    }
}

package io.skjaere.usenet.app.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The TV's cards (TV-3 D13), as a TV shows them (forced on a phone emulator): an Up next card four to the column
 * beside the rail, whole and 16:9; a still card without its still shows the title's poster letterboxed, else its name on
 * a tile; NEW on the picture; a poster's bar of how much was watched; and what OK on a card does, said to TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class TvCardsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun card(title: String) = hasText(title) and hasClickAction()

    /** How many texts say [title], unmerged: the one under the card, and the tile's when the card has no picture. */
    private fun titles(title: String) = compose.onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun fourUpNextCardsFitTheColumnBesideTheRailWholeAt16By9() {
        compose.setTvContent {
            val nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), compose.activity.container.playback) {
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        Box(Modifier.tvFullWidth()) {
                            CardRow((1..5).map { "Card $it" }, key = { it }, gutter = io.skjaere.usenet.app.ui.TvDimens.landscapeGutter) { title ->
                                StillCard(title, stillPath = null, showArtwork = false, onClick = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(card("Card 4")).fetchSemanticsNodes().isNotEmpty() }
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        fun span(title: String) = compose.onNode(card(title)).fetchSemanticsNode().boundsInRoot.let { ((it.left - screen.left) / dp) to ((it.right - screen.left) / dp) }
        val first = span("Card 1")
        val fourth = span("Card 4")
        // Measured at the forced density, where a dp is a pixel and a bit: allow for rounding.
        assertTrue("the first card starts 16 dp into the column (at 144 dp): $first", abs(first.first - 144) <= 2)
        val four = fourth.second - first.first
        assertTrue("four cards and their gutters, $four dp, take the column's 784 dp but its padding at each end, whole", four <= 752 + 1.5f)
        assertTrue("four cards and their gutters, $four dp, leave nothing over in the column", four >= 752 - 4)
        val art = io.skjaere.usenet.app.ui.TvDimens.landscape
        assertEquals("16:9", 16f / 9, art.width.value / art.height.value, 0.02f)
    }

    @Test
    fun aStillCardWithoutItsStillShowsThePosterLetterboxedElseItsNameOnATile() {
        compose.setTvContent {
            Column(Modifier.background(colors.background)) {
                StillCard("Harbor Lights", stillPath = null, showArtwork = true, onClick = {}, posterPath = "/harbor-lights-poster.jpg")
                StillCard("Copper Crown", stillPath = null, showArtwork = true, onClick = {})
                StillCard("Glasswater Bay", stillPath = "/glasswater-still.jpg", showArtwork = false, onClick = {}, posterPath = "/glasswater-poster.jpg")
            }
        }
        compose.waitForIdle()
        assertEquals("the poster in the still's place: the name only under the card", 1, titles("Harbor Lights"))
        assertEquals("no picture at all: the name on its tile too", 2, titles("Copper Crown"))
        assertEquals("artwork off: the tile, whatever pictures there are", 2, titles("Glasswater Bay"))
    }

    @Test
    fun newShowsOnTheCardsPicture() {
        compose.setTvContent {
            Column(Modifier.background(colors.background)) {
                StillCard("The Quiet Orbit", stillPath = null, showArtwork = false, onClick = {}, subtitle = "S01E01 · Pilot", new = true)
                PosterCard("Harbor Lights", posterPath = null, showArtwork = false, onClick = {}, new = true)
                PosterCard("Copper Crown", posterPath = null, showArtwork = false, onClick = {})
            }
        }
        compose.waitForIdle()
        listOf("The Quiet Orbit", "Harbor Lights").forEach { title ->
            assertTrue("$title says NEW", hasText("NEW").matches(compose.onNode(card(title)).fetchSemanticsNode()))
        }
        assertTrue("Copper Crown isn't new", !hasText("NEW").matches(compose.onNode(card("Copper Crown")).fetchSemanticsNode()))
    }

    @Test
    fun aPosterShowsHowMuchWasWatched() {
        compose.setTvContent {
            Column(Modifier.background(colors.background)) {
                PosterCard("Harbor Lights", posterPath = null, showArtwork = false, onClick = {}, progress = 0.4f)
                PosterCard("Copper Crown", posterPath = null, showArtwork = false, onClick = {})
            }
        }
        compose.waitForIdle()
        val bars = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one bar, Harbor Lights'", listOf(0.4f), bars.map { it.config[SemanticsProperties.ProgressBarRangeInfo].current })
    }

    /** OK on a card opens what it is by default; a card that plays says so. */
    @Test
    fun aCardSaysWhatOkOnItDoes() {
        compose.setTvContent {
            Column(Modifier.background(colors.background)) {
                StillCard("Harbor Lights", stillPath = null, showArtwork = false, onClick = {})
                StillCard("Northbound", stillPath = null, showArtwork = false, onClick = {}, clickLabel = "Play")
                PosterCard("Copper Crown", posterPath = null, showArtwork = false, onClick = {})
            }
        }
        compose.waitForIdle()
        fun label(title: String) = compose.onNode(card(title)).fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals("Open", label("Harbor Lights"))
        assertEquals("Play", label("Northbound"))
        assertEquals("Open", label("Copper Crown"))
    }
}

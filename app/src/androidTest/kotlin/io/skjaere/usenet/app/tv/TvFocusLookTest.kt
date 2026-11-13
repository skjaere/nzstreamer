package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.tabsFor
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * How the focus shows on a TV, read from about 3 m (forced on the phone emulator), from the pixels: the components the
 * app owns turn over (the text's cream fills them, the background's near-black writes on them), the Primary button's
 * amber too, and a row's warning dot still shows on the cream; rows of the screens' own, whose words set their colours,
 * take a 3 dp accent ring and the chip's fill, as the release list's cards and sorts do; a pointer remote's hover shows
 * as the focus does; the rail's current tab is tinted while the focus is elsewhere. A phone keeps its look: nothing
 * turns over, rings stay 2 dp.
 */
@RunWith(AndroidJUnit4::class)
class TvFocusLookTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c = AppColors()

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun touch() {
        instrumentation.setInTouchMode(true)
    }

    @Composable
    private fun Components() {
        Column(Modifier.background(c.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Primary", {}, Modifier.fillMaxWidth())
            SecondaryButton("Secondary", {}, Modifier.fillMaxWidth())
            GroupCard(Modifier.padding(0.dp)) {
                NavRow("Usenet providers", "2 · 28 connections") {}
                NavRow("Indexer over http", "indexer.example.org", warn = true) {}
                SwitchRow("Use TLS", true, {})
            }
            Row(Modifier.fillMaxWidth().focusOutline().clickable {}.padding(16.dp)) { Text("A row of a screen's own", color = c.text) }
            TextButton({}, Modifier.fillMaxWidth()) { Text("Material's own") }
        }
    }

    private fun showTv() = compose.setTvContent { Components() }

    private fun showPhone() = compose.setContent { AppTheme(FormFactor.Phone) { Components() } }

    private val primary = hasText("Primary") and hasClickAction()
    private val secondary = hasText("Secondary") and hasClickAction()
    private val navRow = hasText("Usenet providers") and hasClickAction()
    private val warnRow = hasText("Indexer over http") and hasClickAction()
    private val switchRow = hasText("Use TLS") and isToggleable()
    private val ownRow = hasText("A row of a screen's own") and hasClickAction()
    private val material = hasText("Material's own") and hasClickAction()

    private fun focus(matcher: SemanticsMatcher) {
        compose.onNode(matcher).requestFocus()
        compose.waitForIdle()
    }

    /** [matcher]'s element as it shows, with its density (px per dp). */
    private fun image(matcher: SemanticsMatcher): Pair<PixelMap, Float> {
        val node: SemanticsNodeInteraction = compose.onNode(matcher)
        val density = node.fetchSemanticsNode().layoutInfo.density.density
        return node.captureToImage().toPixelMap() to density
    }

    /** The colour [dx] dp in from the element's start, halfway down: inside a pill or a row, clear of its words. */
    private fun startColour(matcher: SemanticsMatcher, dx: Float = 8f): Color {
        val (map, dp) = image(matcher)
        return average(map, (dx * dp).toInt(), map.height / 2)
    }

    private fun average(map: PixelMap, x: Int, y: Int): Color {
        val pixels = (-1..1).flatMap { dx -> (-1..1).map { dy -> map[(x + dx).coerceIn(0, map.width - 1), (y + dy).coerceIn(0, map.height - 1)] } }
        return Color(pixels.map { it.red }.average().toFloat(), pixels.map { it.green }.average().toFloat(), pixels.map { it.blue }.average().toFloat())
    }

    private fun near(a: Color, b: Color, tolerance: Float = 0.06f) =
        abs(a.red - b.red) < tolerance && abs(a.green - b.green) < tolerance && abs(a.blue - b.blue) < tolerance

    private fun Color.hex() = "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    /**
     * The darkest pixel of [words]' own text, as it shows: only the words, not what sits beside them in their element (a
     * switch's thumb or a row's chevron, which would pass for them).
     */
    private fun darkestOf(words: String): Color {
        val map = compose.onNode(hasText(words), useUnmergedTree = true).captureToImage().toPixelMap()
        var best = Color.White
        for (x in 0 until map.width) for (y in 0 until map.height) {
            val p = map[x, y]
            if (p.red + p.green + p.blue < best.red + best.green + best.blue) best = p
        }
        return best
    }

    @Test
    fun theAppsOwnComponentsTurnOverWhenFocused() {
        showTv()
        listOf(
            Triple("Primary", primary, "Primary"), Triple("Secondary", secondary, "Secondary"),
            Triple("a NavRow", navRow, "Usenet providers"), Triple("a SwitchRow", switchRow, "Use TLS"),
        ).forEach { (name, matcher, words) ->
            // The focus elsewhere first: out of touch mode the window gives it to the first button.
            focus(material)
            val before = startColour(matcher)
            assertTrue("$name is cream before it has the focus: ${before.hex()}", !near(before, c.text))
            focus(matcher)
            val after = startColour(matcher)
            assertTrue("$name focused: filled with the text's cream (${c.text.hex()}), not ${after.hex()}", near(after, c.text))
            val ink = darkestOf(words)
            assertTrue("$name focused: its words in the background's near-black (${c.background.hex()}), the darkest of them is ${ink.hex()}", near(ink, c.background, 0.12f))
        }
    }

    /** The contrast between [a] and [b], as WCAG measures it (1 to 21). */
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    /**
     * A NavRow's warning dot (an indexer's plain http, which its row says nothing else of) on a TV: focused, the row
     * turns over and the dot still shows on its cream, at a mark's 3:1 at least, on the row the user is looking at.
     */
    @Test
    fun aWarningDotStillShowsOnARowTurnedOver() {
        showTv()
        focus(warnRow)
        assertTrue("the row turned over", near(startColour(warnRow), c.text))
        val (map, _) = image(warnRow)
        // The most orange pixel of the row's middle band: the dot's.
        var dot = c.text
        for (x in 0 until map.width) for (y in map.height / 4 until map.height * 3 / 4) {
            val p = map[x, y]
            if (p.red - p.blue > dot.red - dot.blue) dot = p
        }
        assertTrue("no warning dot on the focused row: the most orange of it is ${dot.hex()}", dot.red - dot.blue > 0.3f)
        val ratio = contrast(dot, c.text)
        assertTrue("the warning dot (${dot.hex()}) on the cream (${c.text.hex()}): %.2f:1, under 3:1".format(ratio), ratio >= 3f)
    }

    /** One episode's releases, as Search's release list shows them: invented names. */
    private val releases: ResultGroup by lazy {
        fun item(title: String, size: Long) = NewznabItem(
            indexerId = "look", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranking = runBlocking { instrumentation.targetContext.container.rankingContext() }
        ResultGrouper().group(RELEASES.map { (title, size) -> item(title, size) }, ranking).sections.flatMap { it.groups }.first()
    }

    /** A release's card in the list, by its rank ("Best match", "#2"). */
    private fun release(rank: String) = hasText(rank) and hasText(".S02E05.", substring = true) and hasClickAction()

    /** One pixel 1.5 dp in from the element's start, halfway down: on a 3 dp ring, where it has one. */
    private fun ringOf(matcher: SemanticsMatcher): Color {
        val (map, dp) = image(matcher)
        return map[(1.5f * dp).toInt(), map.height / 2]
    }

    /**
     * Search's release list on a TV, where it opens in the right pane: a release and a sort focused take the 3 dp accent
     * ring the screens' own rows take, and Best match has no accent border, which on a TV is the focus's: with the
     * focus on the release under it, Best match doesn't look focused too.
     */
    @Test
    fun aReleaseListsCardsAndSortsTakeTheRingAndBestMatchNoAccentBorder() {
        compose.setTvContent {
            Column(Modifier.background(c.background)) { ReleaseList(releases, "Harbor Lights", onBack = {}) {} }
        }
        focus(release("#2"))
        val second = ringOf(release("#2"))
        assertTrue("the focused release: its 3 dp ring in the accent (${c.accent.hex()}), not ${second.hex()}", near(second, c.accent, 0.1f))
        val best = ringOf(release("Best match"))
        assertTrue("Best match, the focus on the release under it: an accent border, as if focused (${best.hex()})", !near(best, c.accent, 0.2f))
        // Focused, its own border under the ring, not over it.
        focus(release("Best match"))
        val bestFocused = ringOf(release("Best match"))
        assertTrue("Best match focused: its 3 dp ring in the accent, not ${bestFocused.hex()}", near(bestFocused, c.accent, 0.1f))
        val size = hasText("Size") and hasClickAction()
        focus(size)
        val sort = ringOf(size)
        assertTrue("the focused sort: its 3 dp ring in the accent (${c.accent.hex()}), not ${sort.hex()}", near(sort, c.accent, 0.1f))
    }

    @Test
    fun aRowOfAScreensOwnTakesA3DpAccentRingAndTheChipsFill() {
        showTv()
        focus(ownRow)
        val (map, dp) = image(ownRow)
        // One pixel, as a ring is only a few wide at the TV's density.
        val ring = map[(2.5f * dp).toInt(), map.height / 2]
        assertTrue("2.5 dp in: the 3 dp ring in the accent (${c.accent.hex()}), not ${ring.hex()}", near(ring, c.accent, 0.1f))
        // Inside it the chip's grey, under material's focus layer in the accent at 0.28.
        val fill = average(map, (8 * dp).toInt(), map.height / 2)
        val layered = Color(
            c.chip.red + (c.accent.red - c.chip.red) * 0.28f, c.chip.green + (c.accent.green - c.chip.green) * 0.28f, c.chip.blue + (c.accent.blue - c.chip.blue) * 0.28f,
        )
        assertTrue("inside the ring: the chip's fill under the accent's layer (${layered.hex()}), not ${fill.hex()}", near(fill, layered, 0.08f))
    }

    /** material3's controls the app doesn't wrap take the accent's focus layer at 0.28, over their own colours. */
    @Test
    fun materialsOwnControlsTakeTheAccentsFocusLayer() {
        showTv()
        val before = startColour(material)
        focus(material)
        val after = startColour(material)
        val expected = Color(before.red + (c.accent.red - before.red) * 0.28f, before.green + (c.accent.green - before.green) * 0.28f, before.blue + (c.accent.blue - before.blue) * 0.28f)
        assertTrue("focused: the accent's layer at 0.28 over ${before.hex()} (${expected.hex()}), not ${after.hex()}", near(after, expected, 0.08f))
    }

    /** A pointer remote over a button: as focused. */
    @Test
    fun aHoverLooksAsTheFocusDoes() {
        showTv()
        compose.onNode(secondary).performMouseInput { enter(center) }
        compose.waitForIdle()
        val hovered = startColour(secondary)
        assertTrue("hovered: filled with the text's cream, not ${hovered.hex()}", near(hovered, c.text))
    }

    /** The rail's item for the tab shown is tinted while the focus is in the content; focused, it turns over too. */
    @Test
    fun theRailsCurrentTabIsTintedAndTurnsOverWhenFocused() {
        val playback = instrumentation.targetContext.container.playback
        compose.setTvContent {
            val nav = rememberNavController()
            TvShell(nav, tabsFor(FormFactor.Tv), playback) {
                NavHost(nav, startDestination = HomeRoute) { composable<HomeRoute> { Components() } }
            }
        }
        focus(secondary)
        val home = railItem("Home")
        val tinted = startColour(home)
        assertTrue("the current tab's item, the focus elsewhere: the accent's tint (${c.accentTint.hex()}), not ${tinted.hex()}", near(tinted, c.accentTint))
        val other = startColour(railItem("Settings"))
        assertTrue("another tab's item: not tinted, ${other.hex()}", !near(other, c.accentTint))
        // The user's move to the rail: the shell puts a focus it didn't move there back into the content.
        Remote(compose).press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left from the content: the rail's Home", Remote(compose).isFocused(home))
        val turned = startColour(home)
        assertTrue("the current tab's item focused: the text's cream, not ${turned.hex()}", near(turned, c.text))
        // Its 2 dp underline in the accent, at the bottom, under the word.
        val (map, dp) = image(home)
        // Down the middle of its last 6 dp: the line is only two or three pixels at the TV's density.
        val column = ((map.height - (6 * dp).toInt()) until map.height).map { y -> map[map.width / 2, y] }
        assertTrue("focused, the current tab's 2 dp underline in the accent; the bottom shows ${column.map { it.hex() }}", column.any { near(it, c.accent, 0.06f) })
    }

    /**
     * A card in a row: focused, its picture grows 1.1 times inside a 3 dp ring in the accent (read from the screen
     * beside the card, where only the grown picture reaches); without artwork its name is on its tile.
     */
    @Test
    fun aFocusedCardGrowsInsideA3DpAccentRing() {
        compose.setTvContent {
            Column(Modifier.background(c.background).padding(top = 40.dp)) {
                CardRow(listOf("Harbor Lights", "Copper Crown"), key = { it }) { title ->
                    PosterCard(title, posterPath = null, showArtwork = false, onClick = {}, subtitle = "TV series · 2021")
                }
            }
        }
        val first = hasText("Harbor Lights") and hasClickAction()
        val second = hasText("Copper Crown") and hasClickAction()
        assertEquals("the name on the tile, and under it", 2, compose.onAllNodes(hasText("Copper Crown"), useUnmergedTree = true).fetchSemanticsNodes().size)
        fun ringColour(): Color {
            val card = compose.onNode(first).fetchSemanticsNode()
            val dp = card.layoutInfo.density.density
            val root = compose.onRoot().captureToImage().toPixelMap()
            // The picture, 98 x 147 dp at the card's top, grown about its centre: its left edge 4.9 dp out, the ring inside it.
            return root[(card.boundsInRoot.left - 3.4f * dp).toInt(), (card.boundsInRoot.top + 73.5f * dp).toInt()]
        }
        focus(second)
        val before = ringColour()
        assertTrue("beside the card unfocused: the background, not ${before.hex()}", near(before, c.background))
        focus(first)
        val after = ringColour()
        assertTrue("beside the card focused: its grown picture's 3 dp ring in the accent, not ${after.hex()}", near(after, c.accent, 0.1f))
    }

    @Test
    fun onAPhoneNothingTurnsOverAndRingsStay2Dp() {
        showPhone()
        listOf("Primary" to primary, "Secondary" to secondary, "a NavRow" to navRow).forEach { (name, matcher) ->
            focus(matcher)
            val after = startColour(matcher)
            assertTrue("$name focused on a phone: not turned over, ${after.hex()}", !near(after, c.text, 0.2f))
        }
        focus(ownRow)
        val (map, dp) = image(ownRow)
        val ring = average(map, (1f * dp).toInt(), map.height / 2)
        val inside = average(map, (2.7f * dp).toInt(), map.height / 2)
        assertTrue("1 dp in: the phone's ring in the accent, not ${ring.hex()}", near(ring, c.accent, 0.12f))
        assertTrue("2.7 dp in: past the phone's 2 dp ring, not the accent: ${inside.hex()}", !near(inside, c.accent, 0.12f))
    }

    private companion object {
        val RELEASES = listOf(
            // Each plays on the emulators (no Dolby audio), so each has its card.
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.WEB-DL.AAC2.0.H.264-NZST" to 3_200_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.720p.WEB-DL.AAC2.0.H.264-NZST" to 1_400_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.HDTV.x264-NZST" to 2_100_000_000L,
        )
    }
}

package io.skjaere.usenet.app.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.collection.EpisodeRow
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_PHONE
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_TV
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * The Show page's episode rows on a TV (TV-3 D5, D9, D10), forced on a phone emulator: with "Show posters and
 * stills" on a row is 84 dp tall with its still beside the name, 72 dp without; a focused row turns over; its ⋮ is a
 * button of its own that Right reaches, whose menu takes the focus on its first item and gives it back to the ⋮ as it
 * closes; a long OK opens the same menu without playing, and the focus is on the row again after; the page says how a
 * remote plays an episode. Invented episodes; the rows play and mark nothing but these counts.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeRowTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val remote = Remote(compose)
    private var plays = 0
    private var chosen = 0
    private val marks = mutableListOf<Boolean>()
    private val today = LocalDate.now()

    @Before
    fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
    }

    private fun episode(n: Int) = Episode(1, n, NAMES[n - 1], null, today.minusDays(40L - n), 45, null)

    private fun watched(n: Int) = WatchState(WatchKey.episode(SHOW, 1, n), WatchKind.Episode, SHOW, 1, n, title = "Kestrel Wharf S01E0$n", watched = true, updatedAt = 1)

    /** A row for each of [artwork] ("Show posters and stills" on or off for it), in a 540 dp pane as on the Show page. */
    private fun showRows(artwork: List<Boolean>, watched: Set<Int> = emptySet()) {
        compose.setTvContent {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 27.dp).width(540.dp)) {
                artwork.forEachIndexed { i, art ->
                    val e = episode(i + 1)
                    EpisodeRow(
                        e, if (e.episode in watched) watched(e.episode) else null, aired = true,
                        onPlay = { plays++ }, onChoose = { chosen++ }, onMark = { marks += it }, showArtwork = art,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** An episode's row (not its ⋮), by the episode's name. */
    private fun row(n: Int) = hasText(NAMES[n - 1], substring = true) and hasClickAction()

    private fun dp(): Float = compose.tvScreen().width / TV_SIZE.width.value

    /** The share of the pixels inside [matcher]'s node that are [color]. */
    private fun share(matcher: SemanticsMatcher, color: Color): Float {
        compose.waitForIdle()
        val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().toPixelMap()
        val xs = bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width)
        val ys = bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height)
        fun same(a: Color) = abs(a.red - color.red) < 0.03f && abs(a.green - color.green) < 0.03f && abs(a.blue - color.blue) < 0.03f
        return xs.sumOf { x -> ys.count { y -> same(image[x, y]) } }.toFloat() / (xs.count() * ys.count())
    }

    private fun focusRow(n: Int) {
        compose.onNode(row(n)).requestFocus()
        remote.waitForFocus(NAMES[n - 1])
    }

    private fun waitForMenu() = compose.waitUntil(5_000) { exists(hasText("Choose release")) }

    private fun waitForMenuClosed() = compose.waitUntil(5_000) { !exists(hasText("Choose release")) }

    @Test
    fun withArtworkOnARowIs84DpTallWithItsStillBesideTheNameAnd72DpWithout() {
        showRows(listOf(true, false, false))
        val dp = dp()
        val tops = (1..3).map { compose.onNode(row(it)).fetchSemanticsNode().boundsInRoot.top }
        // Within a dp: the forced size's density rounds each part of a row to whole pixels.
        val withStill = (tops[1] - tops[0]) / dp
        val without = (tops[2] - tops[1]) / dp
        assertTrue("a row with a still is %.1f dp tall".format(withStill), abs(withStill - 84) <= 1)
        assertTrue("a row without one is %.1f dp tall".format(without), abs(without - 72) <= 1)
        fun nameInset(n: Int): Float {
            val name = compose.onNode(hasText(NAMES[n - 1], substring = true) and !hasClickAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return (name.left - compose.onNode(row(n)).fetchSemanticsNode().boundsInRoot.left) / dp
        }
        assertTrue("the name beside the 112 dp still starts %.0f dp in".format(nameInset(1)), nameInset(1) >= 112)
        assertTrue("the name of a row without a still starts %.0f dp in".format(nameInset(2)), nameInset(2) < 40)
    }

    @Test
    fun aFocusedRowTurnsOver() {
        showRows(listOf(true, true))
        val cream = AppColors().text
        // The focus on the other row: the window's first focus may have gone to this one.
        focusRow(2)
        val before = share(row(1), cream)
        assertTrue("cream in the row before it has the focus: $before", before < 0.2f)
        focusRow(1)
        val after = share(row(1), cream)
        assertTrue("cream in the focused row: $after", after > 0.5f)
    }

    @Test
    fun rightReachesTheMoreButtonWhoseMenuTakesTheFocusAndGivesItBackOnBack() {
        showRows(listOf(false, false))
        focusRow(1)
        assertEquals("Right from the row", "More for episode 1", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue("Left goes back to the row; it is on ${remote.focused()}", NAMES[0] in remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForMenu()
        remote.waitForFocus("Choose release")
        remote.press(KeyEvent.KEYCODE_BACK)
        waitForMenuClosed()
        remote.waitForFocus("More for episode 1")
        assertEquals("plays and choices", 0 to 0, plays to chosen)
    }

    /** The menu is as wide as the mockup's, 240 dp, whichever of its items has the focus (the focused one's words in bold). */
    @Test
    fun theMenuKeepsItsWidthAsTheFocusMovesAlongIt() {
        showRows(listOf(false, false))
        focusRow(1)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForMenu()
        remote.waitForFocus("Choose release")
        // In the menu's own dp: its window isn't the forced size's.
        fun width() = compose.onNode(hasText("Choose release") and hasClickAction()).fetchSemanticsNode().let { it.size.width / it.layoutInfo.density.density }
        val first = width()
        assertEquals("Mark watched", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val second = width()
        assertTrue("an item spans the 240 dp menu but its 6 dp insets: %.1f, then %.1f dp".format(first, second), abs(first - 228) <= 1 && abs(second - 228) <= 1)
    }

    @Test
    fun markWatchedFromTheMenuLeavesTheFocusOnTheMoreButton() {
        showRows(listOf(false, false))
        focusRow(2)
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForMenu()
        remote.waitForFocus("Choose release")
        assertEquals("Mark watched", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForMenuClosed()
        assertEquals(listOf(true), marks)
        remote.waitForFocus("More for episode 2")
    }

    /**
     * OK held on the row: its menu, at once, without a play; the key still held as the menu shows (its repeats, then its
     * up, a while after) picks nothing in the menu.
     */
    @Test
    fun aLongOkOpensTheMenuWithoutPlayingAndTheFocusIsOnTheRowAfter() {
        showRows(listOf(false, false), watched = setOf(1))
        focusRow(1)
        longOk(compose.mainClock::advanceTimeBy) {
            waitForMenu()
            remote.waitForFocus("Choose release")
            Thread.sleep(500)
        }
        compose.waitForIdle()
        assertEquals("plays, choices and marks", Triple(0, 0, emptyList<Boolean>()), Triple(plays, chosen, marks.toList()))
        assertTrue("a watched episode's menu, still open", exists(hasText("Mark unwatched")))
        remote.press(KeyEvent.KEYCODE_BACK)
        waitForMenuClosed()
        remote.waitForFocus(NAMES[0])
        assertEquals("plays", 0, plays)
    }

    /** The Show page's words on a TV: a remote has no tap. */
    @Test
    fun theShowPageSaysHowARemotePlaysAnEpisode() {
        val show = ShowDetails(
            SHOW, "Kestrel Wharf", null, 2022, null, null, null, "Ended", false, 45,
            listOf(SeasonSummary(1, null, 2, today.minusYears(1), null)),
            lastEpisode = EpisodeSummary(1, 2, NAMES[1], today.minusYears(1)), nextEpisode = null, ids = ExternalIds(),
        )
        val season = Season(SHOW, 1, null, null, listOf(episode(1), episode(2)))
        FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> season.takeIf { id == SHOW } }).use {
            compose.setTvContent { ShowScreen(SHOW, onBack = {}) }
            compose.waitUntil(15_000) { exists(row(2)) }
            // Down the page to it, where the page is one list.
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(SHOW_FOOTNOTE_TV)) }
            compose.waitUntil(5_000) { exists(hasText(SHOW_FOOTNOTE_TV)) }
            assertFalse("the phone's words", exists(hasText(SHOW_FOOTNOTE_PHONE)))
        }
    }

    private companion object {
        /** An invented show; nothing real has the id. */
        const val SHOW = 900061
        val NAMES = listOf("Low Water", "Breakwater", "Undertow")
    }
}

package io.skjaere.usenet.app

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_PHONE
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_TV
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.collection.shortDate
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The Show screen over the real library and the app's coordinator, for an invented show
 * ("Glasswater Bay", an id nothing real has) the TMDB seam answers for: S01E01 aired two weeks
 * ago, S01E02 TMDB lists for five days from now, S01E03 has no date. An episode TMDB lists as not
 * aired yet plays, and opens its menu, as any other: releases often appear before TMDB's date.
 * The app's indexers are swapped for the fake one (`mock-seed indexer`, 10.0.2.2:8089), which has
 * nothing for the show, so a play ends on the no-release sheet's state; no NZB is grabbed. The
 * settings are put back as they were, and episodes a test marks watched unwatched again.
 */
@RunWith(AndroidJUnit4::class)
class ShowScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private var tmdb: FakeTmdb? = null
    private val realSource = PlaybackCoordinator.nzbSource
    private val grabs = AtomicInteger()
    private val today = LocalDate.now()
    private val airs = today.plusDays(5)
    private lateinit var saved: AppSettings
    private var marked = false

    private val show = ShowDetails(
        SHOW, NAME, null, today.year, null, null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 3, today.minusDays(14), null)),
        lastEpisode = EpisodeSummary(1, 1, "Low Water", today.minusDays(14)), nextEpisode = EpisodeSummary(1, 2, "Salt Road", airs), ids = ExternalIds(),
    )

    private val season = Season(
        SHOW, 1, null, null,
        listOf(
            Episode(1, 1, "Low Water", null, today.minusDays(14), 45, null),
            Episode(1, 2, "Salt Road", null, airs, 45, null),
            Episode(1, 3, "Night Ferry", null, null, 45, null),
        ),
    )

    @Before
    fun setUp() {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 8089), 2_000) } }.isSuccess
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", reachable)
        runBlocking {
            assumeTrue("no usable Usenet provider, so a play stops before it searches", container.noUsableProvider() == null)
            saved = container.settings.settings.first()
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(FAKE_INDEXER, "Fake (show test)", "http://10.0.2.2:8089", "fake-indexer-key")
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null })
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { grabs.incrementAndGet(); throw FetchException("Not to be fetched", stopsAll = true) } }
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        tmdb?.close()
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        // Skipped before anything was changed (the fake indexer isn't reachable).
        if (!::saved.isInitialized) return
        runBlocking {
            // Its key goes with it (restore only puts back the settings document).
            if (saved.indexers.none { it.id == FAKE_INDEXER } && container.settings.indexer(FAKE_INDEXER) != null) container.settings.deleteIndexer(FAKE_INDEXER)
            container.settings.restore(saved)
            if (marked) markWatched(false)
        }
    }

    /** S01E01 and, before the date TMDB lists it for, S01E02 marked [watched]. */
    private suspend fun markWatched(watched: Boolean) {
        marked = true
        container.library.markEpisodes(listOf(1, 2).map { EpisodeRef(SHOW, 1, it, WatchTitle.episode(NAME, 1, it)) }, watched)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** An episode's row (not its More button), by the episode's name. */
    private fun row(name: String) = hasText(name) and hasClickAction()

    private fun showScreen() {
        compose.setContent { AppTheme { ShowScreen(SHOW, onBack = {}) } }
        compose.waitUntil(15_000) { exists(row("Night Ferry")) }
    }

    private fun PlayState.subtitle(): String? = when (this) {
        is PlayState.Searching -> subtitle
        is PlayState.NoRelease -> subtitle
        is PlayState.Running -> subtitle
        is PlayState.Failed -> subtitle
        is PlayState.ChoosingFile -> subtitle
        is PlayState.Prechecking -> subtitle
        PlayState.Idle -> null
    }

    @Test
    fun anEpisodeTmdbListsAsNotAiredYetPlaysAndTheSheetSaysWhenTmdbListsIt() {
        showScreen()
        val unaired = row("Salt Road")
        val undated = row("Night Ferry")
        // Each says when it airs, or that it hasn't, and is enabled; TalkBack hears it plays and hasn't aired by TMDB.
        compose.onNode(unaired).assertIsEnabled()
        compose.onNode(undated).assertIsEnabled()
        assertTrue(hasText("Airs ${shortDate(airs)} · 45 min").matches(compose.onNode(unaired).fetchSemanticsNode()))
        assertTrue(hasText("Not aired yet · 45 min").matches(compose.onNode(undated).fetchSemanticsNode()))
        listOf(unaired, undated).forEach { matcher ->
            val node = compose.onNode(matcher).fetchSemanticsNode()
            assertEquals("Hasn't aired yet according to TMDB", node.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals("Play", node.config.getOrNull(SemanticsActions.OnClick)?.label)
            assertTrue(node.config.getOrNull(SemanticsActions.OnLongClick) != null)
        }
        assertEquals(null, compose.onNode(row("Low Water")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNode(hasContentDescription("More for episode 2")).assertIsEnabled()

        compose.onNode(unaired).performClick()
        // Found as any episode is, by the coordinator's episode search.
        assertEquals("S01E02 · Salt Road", container.playback.state.value.subtitle())
        val ended = waitFor("the search for S01E02 to end", 60_000) { container.playback.state.value.takeIf { it is PlayState.NoRelease || it is PlayState.Failed } }
        val none = ended as? PlayState.NoRelease ?: throw AssertionError("the fake indexer has nothing for $NAME, but: $ended")
        assertEquals(NAME to "S01E02 · Salt Road", none.title to none.subtitle)
        assertEquals("TMDB lists this episode for ${shortDate(airs)}. Releases often appear around then. Indexers can also number some shows differently from TMDB.", none.note)
        assertEquals("indexer grabs", 0, grabs.get())
    }

    @Test
    fun aLongPressAndTheMoreButtonOpenTheMenuWhoseChooseReleaseSearches() {
        showScreen()
        compose.onNode(row("Night Ferry")).performTouchInput { longClick() }
        compose.waitUntil(5_000) { exists(hasText("Choose release")) }
        assertTrue(exists(hasText("Mark watched")))
        compose.onNode(hasText("Choose release")).performClick()
        // The chooser searches for S01E03 as Play does, and lists what it found (nothing, on the fake indexer).
        compose.waitUntil(60_000) { exists(hasText("$NAME · ", substring = true)) }
        assertTrue(exists(hasText("S01E03", substring = true)))
        // A TV shows no Back arrow (the remote's Back is its way back), so there the remote's Back closes the list.
        if (FormFactor.of(instrumentation.targetContext) == FormFactor.Tv) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        else compose.onNode(hasContentDescription("Back")).performClick()

        compose.waitUntil(5_000) { exists(hasContentDescription("More for episode 2")) }
        compose.onNode(hasContentDescription("More for episode 2")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Choose release")) }
        assertTrue(exists(hasText("Mark watched")))
        assertEquals("indexer grabs", 0, grabs.get())
    }

    /**
     * S01E02 watched before TMDB's date: the show is up to date, S01E02 no longer to come, and
     * marking the season unwatched takes S01E02 too, so the season starts over.
     */
    @Test
    fun markingTheSeasonUnwatchedTakesAnEpisodeWatchedBeforeTmdbsDate() {
        runBlocking { markWatched(true) }
        showScreen()
        compose.waitUntil(5_000) { exists(hasText("Mark season unwatched")) }
        // Not "Up to date · next episode" and S01E02's date.
        assertTrue(exists(hasText("Up to date")))
        assertFalse(exists(hasText("next episode", substring = true)))
        compose.onNode(hasText("Mark season unwatched")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Play S01E01")) }
        val states = runBlocking { container.library.episodeStates(SHOW).first() }
        assertEquals(listOf(false, false), listOf(1, 2).map { states[1 to it]?.watched })
        assertEquals("indexer grabs", 0, grabs.get())
    }

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull()

    private fun focused(): String = focusedNode()?.let { node ->
        (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")
    } ?: "(nothing)"

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    private fun isFocused(matcher: SemanticsMatcher) = exists(matcher and isFocused())

    /** How many pixels on screen inside [matcher]'s node are [color]. */
    private fun pixelsIn(matcher: SemanticsMatcher, color: Color): Int {
        compose.waitForIdle()
        val bounds = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().toPixelMap()
        val xs = bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width)
        val ys = bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height)
        fun same(a: Color) = abs(a.red - color.red) < 0.03f && abs(a.green - color.green) < 0.03f && abs(a.blue - color.blue) < 0.03f
        return xs.sumOf { x -> ys.count { y -> same(image[x, y]) } }
    }

    /** A TV remote: the D-pad reaches the episode, outlined as the app's other rows; OK plays it, and held, opens its menu. */
    @Test
    fun aRemoteReachesTheEpisodeOutlinedAndOkPlaysIt() {
        instrumentation.setInTouchMode(false)
        showScreen()
        val unaired = row("Salt Road")
        val accent = AppColors().accent
        assertEquals("accent pixels in the row before it has the focus", 0, pixelsIn(unaired, accent))
        val seen = mutableListOf(focused())
        repeat(15) { if (!isFocused(unaired)) seen += press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("never focused S01E02; focus went ${seen.joinToString(" → ")}", isFocused(unaired))
        val outline = pixelsIn(unaired, accent)
        assertTrue("$outline accent pixels in the focused row", outline > 500)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("S01E02 · Salt Road", container.playback.state.value.subtitle())
        // Started is all this checks: stopped here, it spends no more of the fake indexer's calls.
        container.playback.cancel()

        // OK held down opens the menu, as a long press does (the row times the hold on the test's clock).
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.mainClock.advanceTimeBy(1_500)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitUntil(5_000) { exists(hasText("Choose release")) }
        // Held, it doesn't play as well.
        assertEquals(PlayState.Idle, container.playback.state.value)
        assertEquals("indexer grabs", 0, grabs.get())
    }

    /**
     * #160: an episode's ⋮ is a button of its own beside the row, so a remote's Right reaches it and Left goes back; OK
     * opens its menu, and Back closes it with the focus on the ⋮ again.
     */
    @Test
    fun aRemotesRightFromAnEpisodeReachesItsMoreButtonWhoseMenuOkOpens() {
        instrumentation.setInTouchMode(false)
        showScreen()
        val first = row("Low Water")
        val seen = mutableListOf(focused())
        repeat(15) { if (!isFocused(first)) seen += press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("never focused S01E01; focus went ${seen.joinToString(" → ")}", isFocused(first))
        assertEquals("Right from the row", "More for episode 1", press(KeyEvent.KEYCODE_DPAD_RIGHT))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left goes back to the row; it is on ${focused()}", isFocused(first))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { exists(hasText("Choose release")) }
        assertTrue(exists(hasText("Mark watched")))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !exists(hasText("Choose release")) }
        assertEquals("the focus once the menu closed", "More for episode 1", focused())
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    /**
     * A phone's rows and menu as they were before the ⋮ became a button of its own: a watched row's ✓ 12 dp from its ⋮,
     * and the menu's items as wide as the menu, their press shown across it.
     */
    @Test
    fun aPhonesWatchedRowAndItsMenuLookAsTheyDid() {
        runBlocking { markWatched(true) }
        showScreen()
        compose.waitUntil(5_000) { exists(hasText("✓")) }
        val tick = compose.onAllNodes(hasText("✓"), useUnmergedTree = true).fetchSemanticsNodes().first()
        val dp = tick.layoutInfo.density.density
        val more = compose.onNode(hasContentDescription("More for episode 1")).fetchSemanticsNode()
        val gap = (more.boundsInRoot.left - tick.boundsInRoot.right) / dp
        compose.onNode(hasContentDescription("More for episode 1")).performClick()
        compose.waitUntil(5_000) { exists(hasText("Mark unwatched")) }
        val item = compose.onNode(hasText("Mark unwatched") and hasClickAction()).fetchSemanticsNode()
        val menu = generateSequence(item) { it.parent }.last().boundsInRoot
        val itemDp = item.layoutInfo.density.density
        val insets = (item.boundsInRoot.left - menu.left) / itemDp to (menu.right - item.boundsInRoot.right) / itemDp
        // 12 dp to the ⋮'s 48 dp target, whose 40 dp button is 4 dp in.
        assertTrue("the ✓ %.1f dp from the ⋮'s button; the item %.1f and %.1f dp in from the menu's sides".format(gap, insets.first, insets.second),
            abs(gap - 16) <= 1 && abs(insets.first) <= 1 && abs(insets.second) <= 1)
    }

    /** The page's words on a phone: a tap on an episode plays it, its ⋮ chooses. */
    @Test
    fun theFootnoteSaysATapPlaysAndTheMoreButtonChooses() {
        showScreen()
        compose.waitUntil(5_000) { exists(hasText(SHOW_FOOTNOTE_PHONE)) }
        assertFalse("a TV's words", exists(hasText(SHOW_FOOTNOTE_TV)))
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 30_000, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private companion object {
        /** An invented show; nothing real has the id. */
        const val SHOW = 900041
        const val NAME = "Glasswater Bay"
        const val FAKE_INDEXER = "show-device-test"
    }
}

package io.skjaere.usenet.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.home.HomeCollection
import io.skjaere.usenet.app.home.HomeCollectionRow
import io.skjaere.usenet.app.home.HomeContent
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.MARK_WATCHED
import io.skjaere.usenet.app.home.REMOVE_FROM_UP_NEXT
import io.skjaere.usenet.app.home.UpNextEdits
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.metadata.EpisodeRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's content with a TV remote (focus starts on the search field, every element takes it) and at twice the font size
 * (nothing cut); Up next's menu, from each row's ⋮ or a long press, with TalkBack's actions for it, and the Undo bar after
 * Remove (L21, L23).
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun show(id: Int, name: String) = CollectedShow(CollectedShow.idFor(id), id, name = name, addedAt = 0, updatedAt = 0)

    private fun state(id: Int, name: String, kind: WatchKind = WatchKind.Episode) = WatchState(
        if (kind == WatchKind.Episode) WatchKey.episode(id, 2, 5) else WatchKey.movie(id), kind, showTmdbId = id.takeIf { kind == WatchKind.Episode },
        season = 2, episode = 5, movieTmdbId = id.takeIf { kind == WatchKind.Movie }, title = name, positionMs = 900_000, durationMs = 2_700_000, updatedAt = 0,
    )

    private val harbor = show(1, "Harbor Lights")
    private val orbit = show(2, "The Quiet Orbit")
    private val glasshouse = show(3, "Glasshouse")

    private val home = HomeState(
        recent = listOf(RecentSearch("harbor lights s02", "harbor lights s02", 0, 0), RecentSearch("copper valley", "copper valley", 0, 0)),
        upNext = listOf(
            UpNextRow("Harbor Lights", "S02E05 · The Long Tide · 30 min left", 0.38f, false, null, UpNextPlay.Resume(state(1, "Harbor Lights S02E05")), "Resume Harbor Lights S02E05"),
            UpNextRow("The Quiet Orbit", "S01E02 · Second Light · 8 unwatched", null, true, null, UpNextPlay.NextEpisode(orbit, EpisodeRef(1, 2)), "Play The Quiet Orbit S01E02"),
            UpNextRow("Copper Valley at the End of the Long Northern Road", "2019 · 1 h 12 min left", 0.72f, false, null, UpNextPlay.Resume(state(10, "Copper Valley", WatchKind.Movie)), "Resume Copper Valley"),
        ),
        collection = HomeCollection(
            6, 0,
            listOf(
                HomeCollectionRow(TitleKey.show(harbor.tmdbId), harbor.name, "Next: S02E01 · 10 unwatched"),
                HomeCollectionRow(TitleKey.show(glasshouse.tmdbId), glasshouse.name, "Up to date"),
            ),
        ),
        hasTmdbKey = true,
    )

    private val shownState = mutableStateOf<HomeState?>(null)
    private var scale by mutableFloatStateOf(1f)
    private val fake = UpNextEditsFake(shownState)

    /** What the menu's items picked, as (item, row title), when [showHome] records them rather than act. */
    private val picked = mutableListOf<Pair<String, String>>()
    private val recording = UpNextEdits(remove = { picked += REMOVE_FROM_UP_NEXT to it.title }, markWatched = { picked += MARK_WATCHED to it.title })

    /** Home on [state] at [fontScale] ([scale], which a test may change after), Up next's menu and bar as [edits] says, else as [fake] does them. */
    private fun showHome(fontScale: Float = 1f, state: HomeState = home, edits: UpNextEdits? = null) {
        scale = fontScale
        shownState.value = state
        val context = instrumentation.targetContext
        compose.setContent {
            val scaled = remember(scale) { context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = scale }) }
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme { HomeContent(shownState.value, artwork = false, {}, {}, {}, {}, {}, {}, {}, {}, edits = edits ?: fake.edits()) }
            }
        }
    }

    @After
    fun touchMode() {
        instrumentation.setInTouchMode(true)
        fake.close()
    }

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" | ")

    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull()?.words() ?: "(nothing)"

    private fun press(key: Int): String {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitForIdle()
        return focused()
    }

    @Test
    fun aRemoteStartsOnTheSearchFieldAndReachesEveryRowAndButton() {
        instrumentation.setInTouchMode(false)
        showHome()
        compose.waitUntil(5_000) { "Search your indexers" in focused() }
        val seen = mutableListOf(focused())
        seen += press(KeyEvent.KEYCODE_DPAD_RIGHT)
        seen += press(KeyEvent.KEYCODE_DPAD_LEFT)
        // Down the screen, and right on each row to its Play button and its ⋮.
        repeat(12) {
            val now = press(KeyEvent.KEYCODE_DPAD_DOWN)
            seen += now
            if (home.upNext.any { row -> row.title in now }) {
                seen += press(KeyEvent.KEYCODE_DPAD_RIGHT)
                seen += press(KeyEvent.KEYCODE_DPAD_RIGHT)
                press(KeyEvent.KEYCODE_DPAD_LEFT)
                press(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            // The chips sit side by side.
            if (home.recent.any { it.query in now }) {
                seen += press(KeyEvent.KEYCODE_DPAD_LEFT)
                seen += press(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
        val expected = listOf(
            "Search your indexers", "Settings", "harbor lights s02", "copper valley",
            "S02E05 · The Long Tide · 30 min left", "Resume Harbor Lights S02E05", "More for Harbor Lights",
            "S01E02 · Second Light · 8 unwatched", "Play The Quiet Orbit S01E02", "More for The Quiet Orbit",
            "2019 · 1 h 12 min left", "Resume Copper Valley", "More for Copper Valley at the End of the Long Northern Road",
            "See all", "Next: S02E01 · 10 unwatched", "Up to date", "Open a .nzb file",
        )
        expected.forEach { words -> assertTrue("never focused: $words; focus went ${seen.joinToString(" → ")}", seen.any { words in it }) }
    }

    /**
     * Played from Up next, a show moves up (its next episode is now in progress): back on Home, a
     * remote's focus is still on that show's Play button, not on whatever row took its place.
     */
    @Test
    fun aRemoteStaysOnTheShowItPlayedWhenUpNextReorders() {
        instrumentation.setInTouchMode(false)
        val harborRow = home.upNext[0]
        val before = home.copy(upNext = home.upNext.take(2))
        val orbitStarted = UpNextRow(
            "The Quiet Orbit", "S01E02 · Second Light · 30 min left", 0.33f, true, null, UpNextPlay.Resume(state(2, "The Quiet Orbit S01E02")), "Resume The Quiet Orbit S01E02",
        )
        val after = home.copy(upNext = listOf(orbitStarted, harborRow))
        var shown by mutableStateOf(before)
        val played = mutableListOf<UpNextRow>()
        compose.setContent {
            AppTheme { HomeContent(shown, artwork = false, {}, {}, {}, { played += it }, {}, {}, {}, {}) }
        }
        compose.waitUntil(5_000) { "Search your indexers" in focused() }
        compose.onNode(hasContentDescription("Play The Quiet Orbit S01E02")).requestFocus()
        compose.waitUntil(5_000) { "Play The Quiet Orbit S01E02" in focused() }
        shown = after
        compose.waitForIdle()
        assertTrue("focus is on ${focused()}", "Resume The Quiet Orbit S01E02" in focused())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(orbitStarted), played)
    }

    /** A release played without TMDB, named from the release; its menu has Remove only. */
    private val northbound = UpNextRow(
        "Northbound", "2019 · 30 min left", 0.4f, false, null,
        UpNextPlay.Resume(WatchState(WatchKey.release("n-northbound", 0), WatchKind.Release, title = "Northbound.2019.1080p.BluRay.x264-NZST", positionMs = 1_200_000, durationMs = 3_000_000, updatedAt = 0)),
        "Resume Northbound 2019",
    )

    /** The open menu's items' words, in order. */
    private fun menuItems(): List<String> = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map { it.words() }

    private fun menuItem(words: String) = hasText(words) and hasAnyAncestor(isPopup()) and hasClickAction()

    /** The row saying [line] under its title: its playing part, which has the row's words and its TalkBack actions. */
    private fun rowBody(line: String) = hasText(line) and hasClickAction()

    private fun scrolledTo(matcher: SemanticsMatcher) = compose.onNode(hasScrollAction()).performScrollToNode(matcher)

    /** The node's box on the screen, in px. */
    private fun SemanticsNode.onScreen() = Rect(positionOnScreen, size.toSize())

    /** Home's own window on the screen: with a menu open, its popup is a window (a root) of its own. */
    private fun homeWindow(): Rect = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.size.width * it.size.height }.onScreen()

    /** [matcher]'s text inside Home's window and whole (not cut, nor ending in an ellipsis): null, else what is wrong. */
    private fun cut(matcher: SemanticsMatcher, what: String): String? {
        val screen = homeWindow()
        val node = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null } ?: return "$what: no text"
        val layout = node.textLayout()!!
        val b = node.onScreen()
        return when {
            layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1) -> "$what is cut at ${scale}x"
            b.left < screen.left || b.right > screen.right || b.bottom > screen.bottom -> "$what ($b) leaves the screen ($screen) at ${scale}x"
            else -> null
        }
    }

    /**
     * L21: one menu from a row's ⋮ or a long press on the row: Remove from Up next on every row, Mark watched on an episode
     * or a film in progress (W3); a next episode and a release played without TMDB, Remove only. At 1x and 2x, its items whole.
     */
    @Test
    fun theMenuOpensFromTheMoreButtonAndALongPressWithItsItemsByTheRowsKind() {
        showHome(state = home.copy(upNext = home.upNext + northbound), edits = recording)
        val problems = mutableListOf<String>()
        listOf(1f, 2f).forEach { s ->
            scale = s
            compose.waitForIdle()
            picked.clear()
            fun open(row: UpNextRow, fromMore: Boolean) {
                if (fromMore) {
                    val more = hasContentDescription("More for ${row.title}")
                    scrolledTo(more)
                    compose.onNode(more).performClick()
                } else {
                    scrolledTo(rowBody(row.line))
                    compose.onNode(rowBody(row.line)).performTouchInput { longClick() }
                }
                compose.waitUntil(5_000) { menuItems().isNotEmpty() }
            }
            fun check(row: UpNextRow, fromMore: Boolean, items: List<String>, pick: String) {
                open(row, fromMore)
                assertEquals("${row.title}'s menu at ${s}x", items, menuItems())
                items.forEach { item -> cut(hasText(item) and hasAnyAncestor(isPopup()), item)?.let { problems += it } }
                compose.onNode(menuItem(pick)).performClick()
                compose.waitUntil(5_000) { menuItems().isEmpty() }
            }
            check(home.upNext[0], fromMore = true, listOf(REMOVE_FROM_UP_NEXT, MARK_WATCHED), MARK_WATCHED)
            check(home.upNext[1], fromMore = false, listOf(REMOVE_FROM_UP_NEXT), REMOVE_FROM_UP_NEXT)
            check(home.upNext[2], fromMore = false, listOf(REMOVE_FROM_UP_NEXT, MARK_WATCHED), REMOVE_FROM_UP_NEXT)
            check(northbound, fromMore = true, listOf(REMOVE_FROM_UP_NEXT), REMOVE_FROM_UP_NEXT)
            assertEquals(
                listOf(MARK_WATCHED to "Harbor Lights", REMOVE_FROM_UP_NEXT to "The Quiet Orbit", REMOVE_FROM_UP_NEXT to home.upNext[2].title, REMOVE_FROM_UP_NEXT to "Northbound"),
                picked.toList(),
            )
        }
        assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    /** TalkBack has no long press on the row: the menu's items are the row's own actions, and do what the items do. */
    @Test
    fun talkBackHasTheMenusItemsAsEachRowsActions() {
        showHome(state = home.copy(upNext = home.upNext + northbound), edits = recording)
        fun actions(row: UpNextRow) = compose.onNode(rowBody(row.line)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf(REMOVE_FROM_UP_NEXT, MARK_WATCHED), actions(home.upNext[0]).map { it.label })
        assertEquals(listOf(REMOVE_FROM_UP_NEXT), actions(home.upNext[1]).map { it.label })
        assertEquals(listOf(REMOVE_FROM_UP_NEXT, MARK_WATCHED), actions(home.upNext[2]).map { it.label })
        assertEquals(listOf(REMOVE_FROM_UP_NEXT), actions(northbound).map { it.label })
        val mark = actions(home.upNext[2]).first { it.label == MARK_WATCHED }
        val remove = actions(home.upNext[1]).first { it.label == REMOVE_FROM_UP_NEXT }
        compose.runOnUiThread { mark.action() }
        compose.runOnUiThread { remove.action() }
        assertEquals(listOf(MARK_WATCHED to home.upNext[2].title, REMOVE_FROM_UP_NEXT to "The Quiet Orbit"), picked.toList())
    }

    /**
     * L23, by touch at 2x: Remove takes the row out and the Undo bar shows at Home's foot, its words whole, for 10 s or more
     * (the accessibility timeout); nothing takes the focus. Undo puts the row back in its place.
     */
    @Test
    fun afterRemoveTheUndoBarShowsAtHomesFootAndUndoPutsTheRowBack() {
        showHome(fontScale = 2f)
        compose.onNode(hasContentDescription("More for Harbor Lights")).performClick()
        compose.onNode(menuItem(REMOVE_FROM_UP_NEXT)).performClick()
        val message = hasText("Removed “Harbor Lights” from Up next")
        compose.waitUntil(5_000) { compose.onAllNodes(message).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Harbor Lights"), fake.removed.map { it.title })
        assertTrue("Harbor Lights' row is gone", compose.onAllNodes(hasContentDescription("More for Harbor Lights")).fetchSemanticsNodes().isEmpty())
        listOfNotNull(cut(message, "the bar's message"), cut(hasText("Undo"), "Undo")).forEach { throw AssertionError(it) }
        val screen = homeWindow()
        val bar = compose.onNode(hasText("Undo") and hasClickAction()).fetchSemanticsNode().onScreen()
        assertTrue("the bar ($bar) at the screen's foot ($screen)", bar.bottom > screen.bottom - 80 * compose.density.density)
        assertTrue("the bar's time: ${fake.timeouts}", fake.timeouts.single() >= 10_000)
        assertFalse("on touch nothing takes the focus", compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("More for Harbor Lights")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, fake.undone)
        assertTrue("the bar went with the Undo", compose.onAllNodes(message).fetchSemanticsNodes().isEmpty())
        assertEquals(home.upNext, shownState.value!!.upNext)
    }

    /**
     * L23 with a remote: Undo takes the focus as the bar shows; Up from it hands the focus to the row after the one
     * removed, and the bar goes; Back does too, after a film marked watched, to the row before (the film was the last).
     */
    @Test
    fun withARemoteUndoTakesTheFocusAndUpOrBackHandsItToTheNextRowElseTheOneBefore() {
        instrumentation.setInTouchMode(false)
        showHome()
        compose.waitUntil(5_000) { "Search your indexers" in focused() }
        val more = hasContentDescription("More for Harbor Lights")
        compose.onNode(more).requestFocus()
        compose.onNode(more).performClick()
        compose.onNode(menuItem(REMOVE_FROM_UP_NEXT)).performClick()
        compose.waitUntil(5_000) { "Undo" in focused() }
        assertTrue("Up from Undo: the next row; focus on ${focused()}", "S01E02 · Second Light" in press(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue("the bar goes", compose.onAllNodes(hasText("Removed “Harbor Lights” from Up next")).fetchSemanticsNodes().isEmpty())

        val film = home.upNext[2]
        val filmMore = hasContentDescription("More for ${film.title}")
        compose.onNode(filmMore).requestFocus()
        compose.onNode(filmMore).performClick()
        compose.onNode(menuItem(MARK_WATCHED)).performClick()
        compose.waitUntil(5_000) { "Undo" in focused() }
        assertTrue(compose.onAllNodes(hasText("Marked “${film.title}” watched")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("Back from Undo: the row before; focus on ${focused()}", "S01E02 · Second Light" in press(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf(film), fake.marked.toList())
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.singleOrNull()
    }

    @Test
    fun atTwiceTheFontSizeRowsGrowAndNothingIsCut() {
        showHome(fontScale = 2f)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val words = listOf(
            "Search your indexers", "harbor lights s02", "Copper Valley at the End of the Long Northern Road", "S02E05 · The Long Tide · 30 min left",
            "S01E02 · Second Light · 8 unwatched", "NEW", "2019 · 1 h 12 min left", "Collection · 6 shows", "See all", "Next: S02E01 · 10 unwatched", "Up to date",
            "Open a .nzb file",
        )
        words.forEach { text ->
            val matcher = hasText(text, ignoreCase = true)
            compose.onNode(hasScrollAction()).performScrollToNode(matcher)
            val node = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first { it.textLayout() != null }
            val layout = node.textLayout()!!
            assertFalse(
                "'$text' is cut: ${layout.size} for ${layout.multiParagraph.width} x ${layout.multiParagraph.height} in ${layout.layoutInput.constraints}, " +
                    "lines ${layout.lineCount}",
                layout.didOverflowHeight || layout.isLineEllipsized(layout.lineCount - 1),
            )
            val bounds = node.boundsInRoot
            assertTrue("'$text' ($bounds) leaves the screen ($screen) sideways", bounds.left >= screen.left && bounds.right <= screen.right)
        }
        // A row as tall as its words: the long title wraps instead of being cut.
        val title = compose.onAllNodes(hasText("Copper Valley at the End of the Long Northern Road"), useUnmergedTree = true).fetchSemanticsNodes().first { it.textLayout() != null }
        assertTrue("the long title is on ${title.textLayout()!!.lineCount} line(s)", title.textLayout()!!.lineCount > 1)
    }
}

package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.TmdbLookup
import io.skjaere.usenet.app.collection.CANCEL
import io.skjaere.usenet.app.collection.SEASON_LOAD_FAILED
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_TV
import io.skjaere.usenet.app.collection.SHOW_LOAD_FAILED
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.collection.SkeletonRowKey
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.metadata.TmdbError
import io.skjaere.usenet.metadata.TmdbException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Collections
import kotlin.math.abs

/**
 * The Show page on a TV (TV-3), in the TV shell forced on a phone emulator, over the real library and
 * an invented show ("Saltmarsh Light", an id nothing real has) the TMDB seam answers for: three seasons of three
 * episodes and a special. Season 1 and S02E01 are watched, S02E02 half way. The first focus is on Resume, or the
 * selected season's chip when up to date; the chips select the season the focus settles on, one season asked of TMDB per
 * chip stopped at; Down from the chips enters at the next-up episode and Up from the first episode goes back to the chip;
 * an episode's ⋮ is reached with Right and its menu offers Choose release; a long OK opens it without playing; Back from
 * the chooser, or its Cancel, puts the focus on what opened it, Choose release on the left pane included; a season or a
 * show TMDB can't answer for says so with Try again; both panes keep inside the safe area with every text shown in full
 * at 1x and 1.3x; the OK that opened the page plays nothing. The app's indexers are swapped for the fake one (which has
 * nothing for the show) or one that never answers; the episodes' states are put back.
 */
@RunWith(AndroidJUnit4::class)
class TvShowScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val remote = Remote(compose)
    private val indexers = PageIndexers(INDEXER)
    private var tmdb: FakeTmdb? = null
    private var scale by mutableFloatStateOf(1f)
    private val today = LocalDate.now()
    private val aired = today.minusYears(1)
    /** The seasons TMDB was asked for, in turn. */
    private val seasonLoads = Collections.synchronizedList(mutableListOf<Int>())
    @Volatile private var failSeason3 = false

    private val show = ShowDetails(
        SHOW, NAME, null, aired.year, "A ferryman's daughter keeps the marsh light burning after the last boat stops running.", null, null, "Returning Series", true, 45,
        listOf(SeasonSummary(1, null, 3, aired, null), SeasonSummary(2, null, 3, aired, null), SeasonSummary(3, null, 3, aired, null), SeasonSummary(0, null, 1, aired, null)),
        lastEpisode = EpisodeSummary(3, 3, NAMES.getValue(3)[2], aired.plusDays(60)), nextEpisode = null, ids = ExternalIds(),
    )

    private fun season(n: Int, id: Int = SHOW) = Season(id, n, null, null, NAMES.getValue(n).mapIndexed { i, name -> Episode(n, i + 1, name, null, aired.plusDays(n * 20L + i), 45, null) })

    /** A show of nine seasons, more chips than their line holds: three episodes each, ten in the seventh. */
    private val wide = ShowDetails(
        WIDE, WIDE_NAME, null, aired.year, "Nine winters of a harbour town that counts its boats home each night.", null, null, "Returning Series", true, 45,
        (1..9).map { SeasonSummary(it, null, wideCount(it), aired, null) },
        lastEpisode = EpisodeSummary(9, 3, wideName(9, 3), aired.plusDays(9 * 20L + 2)), nextEpisode = null, ids = ExternalIds(),
    )

    private fun wideSeason(n: Int) = Season(WIDE, n, null, null, (1..wideCount(n)).map { i -> Episode(n, i, wideName(n, i), null, aired.plusDays(n * 20L + i - 1), 45, null) })

    /** The seasons of the nine-season show TMDB was asked for, in turn. */
    private val wideLoads = Collections.synchronizedList(mutableListOf<Int>())

    private fun wideRef(season: Int, episode: Int) = EpisodeRef(WIDE, season, episode, WatchTitle.episode(WIDE_NAME, season, episode))

    /** The nine-season show's episodes watched up to [season]'s [episode], every one of them when null. */
    private fun watchedWide(season: Int? = null, episode: Int = 0) = runBlocking {
        val all = (1..9).flatMap { s -> (1..wideCount(s)).map { s to it } }
        val watched = if (season == null) all else all.filter { (s, e) -> s < season || (s == season && e <= episode) }
        container.library.markEpisodes(watched.map { (s, e) -> wideRef(s, e) }, watched = true)
    }

    /** The nine-season show's page opened, the season it opens on shown and its first focus on [firstFocus]. */
    private fun openWide(season: Int, firstFocus: String) {
        compose.setTvPage({ scale }) { ShowScreen(WIDE, onBack = {}) }
        waitFor(row(wideName(season, wideCount(season))), 15_000)
        remote.waitForFocus(firstFocus)
        settle()
    }

    @Before
    fun setUp() {
        instrumentation.setInTouchMode(false)
        tmdb = FakeTmdb(
            shows = mapOf(SHOW to show, LONG to show.copy(tmdbId = LONG, name = LONG_NAME), WIDE to wide),
            seasons = { id, n ->
                when (id) {
                    SHOW -> {
                        seasonLoads += n
                        if (n == 3 && failSeason3) throw TmdbException(TmdbError.Network("Unable to resolve host"))
                        season(n)
                    }
                    LONG -> season(n, LONG)
                    WIDE -> wideSeason(n).also { wideLoads += n }
                    else -> null
                }
            },
        )
    }

    @After
    fun restore() {
        instrumentation.setInTouchMode(true)
        tmdb?.close()
        container.playback.cancel()
        indexers.restore()
        runBlocking {
            container.library.markEpisodes(NAMES.flatMap { (s, names) -> names.indices.map { ref(s, it + 1) } }, watched = false)
            container.library.markEpisodes((1..9).flatMap { s -> (1..wideCount(s)).map { wideRef(s, it) } }, watched = false)
        }
    }

    private fun ref(season: Int, episode: Int) = EpisodeRef(SHOW, season, episode, WatchTitle.episode(NAME, season, episode))

    /** Season 1 and S02E01 watched; S02E02 a quarter of the way in: Resume S02E02. Up to date: every episode of the three seasons watched. */
    private fun watched(upToDate: Boolean = false) = runBlocking {
        if (upToDate) {
            container.library.markEpisodes((1..3).flatMap { s -> (1..3).map { ref(s, it) } }, watched = true)
        } else {
            container.library.markEpisodes(listOf(ref(1, 1), ref(1, 2), ref(1, 3), ref(2, 1)), watched = true)
            val template = WatchState(WatchKey.episode(SHOW, 2, 2), WatchKind.Episode, SHOW, 2, 2, title = WatchTitle.episode(NAME, 2, 2), updatedAt = 0)
            container.library.recordProgress(template, RESUME_MS, 45 * 60_000L)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) { exists(matcher) }

    /** An episode's row (not its ⋮), by its name. */
    private fun row(name: String) = hasText(name, substring = true) and hasClickAction()

    private fun chip(label: String) = hasText(label) and hasClickAction()

    /** The page opened, its episodes shown and its first focus taken; the uptime it opened at. */
    private fun openPage(upToDate: Boolean = false, firstFocus: String = if (upToDate) "S3 ✓" else RESUME): Long {
        watched(upToDate)
        val opened = SystemClock.uptimeMillis()
        compose.setTvPage({ scale }) { ShowScreen(SHOW, onBack = {}) }
        waitFor(row(NAMES.getValue(if (upToDate) 3 else 2)[0]), 15_000)
        remote.waitForFocus(firstFocus)
        // Past the page's first 400 ms, whose OKs a TV takes for the one that opened it.
        settle()
        return opened
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
    }

    private fun focus(matcher: SemanticsMatcher, words: String) {
        compose.onNode(matcher).requestFocus()
        remote.waitForFocus(words)
    }

    @Test
    fun theFirstFocusIsOnResumeAndThePageSaysHowARemotePlays() {
        openPage()
        assertTrue("the TV's footnote", exists(hasText(SHOW_FOOTNOTE_TV)))
        assertTrue("the title, the page's heading", compose.onAllNodes(hasText(NAME)).fetchSemanticsNodes().any { it.config.contains(SemanticsProperties.Heading) })
        // A TV plays itself: no Play on button (L36).
        assertFalse("a Play on button on a TV", exists(hasContentDescription("Play on:", substring = true)))
    }

    @Test
    fun upToDateTheFirstFocusIsOnTheSelectedSeasonsChip() {
        openPage(upToDate = true)
    }

    /** Along the chips without stopping: TMDB is asked for the season the focus stops at, not for each one passed. */
    @Test
    fun theChipsLoadOneSeasonForEachChipTheFocusStopsAt() {
        openPage()
        assertEquals("on opening", listOf(2), seasonLoads.toList())
        focus(chip("Season 2 · 1/3"), "Season 2")
        // Right twice at once: S3 passed, the specials stopped at.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { seasonLoads.size == 2 }
        settle()
        assertEquals("stopped at the specials", listOf(2, 0), seasonLoads.toList())
        // Back along to S1: S3 and S2 passed.
        repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT) }
        compose.waitUntil(5_000) { seasonLoads.size == 3 }
        settle()
        assertEquals("stopped at S1", listOf(2, 0, 1), seasonLoads.toList())
        assertTrue("S1's episodes shown", exists(row(NAMES.getValue(1)[0])))
    }

    @Test
    fun downFromTheChipsEntersAtTheNextUpEpisodeAndUpFromTheFirstGoesBackToTheChip() {
        openPage()
        focus(chip("Season 2 · 1/3"), "Season 2")
        assertTrue("Down from the chips: the next-up episode, S02E02", NAMES.getValue(2)[1] in remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("Up: S02E01", NAMES.getValue(2)[0] in remote.press(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue("Up from the first episode: the selected chip", "Season 2" in remote.press(KeyEvent.KEYCODE_DPAD_UP))
    }

    /**
     * The selected season's chip past the end of the chips' line (season 7 of 9, the line showing the first few): Up from
     * its first episode goes to it, not to a chip on screen whose dwell would switch the season.
     */
    @Test
    fun upFromTheFirstEpisodeGoesToTheSelectedChipPastTheEndOfTheChipsLine() {
        watchedWide(7, 7)
        openWide(7, "Play S07E08")
        // The list opens at the next-up episode: up along it to the first, then to the chips.
        focus(row(wideName(7, 7)), wideName(7, 7))
        val first = remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, wideName(7, 1), max = 8)
        assertTrue("never reached S07E01; at $first", wideName(7, 1) in first)
        assertEquals("Up from the first episode: the selected chip", "Season 7 · 7/10", remote.press(KeyEvent.KEYCODE_DPAD_UP))
        settle()
        assertEquals("no season but the 7th asked for", listOf(7), wideLoads.toList())
        assertTrue("season 7's episodes still shown", exists(row(wideName(7, 1))))
    }

    /**
     * The chips' line scrolled back to its start while season 7 stays selected (as a quick run of Left along the chips,
     * then Down before the dwell fires, leaves it), so its chip isn't composed: Up from the first episode brings the chip
     * back into view and goes to it, not to a chip on screen whose dwell would switch the season.
     */
    @Test
    fun upFromTheFirstEpisodeBringsBackTheSelectedChipTheLineWasScrolledAwayFrom() {
        watchedWide(7, 7)
        openWide(7, "Play S07E08")
        focus(row(wideName(7, 7)), wideName(7, 7))
        val first = remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, wideName(7, 1), max = 8)
        assertTrue("never reached S07E01; at $first", wideName(7, 1) in first)
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(chip("Season 7 · 7/10"))).performScrollToIndex(0)
        compose.waitForIdle()
        assertTrue("season 7's chip still on the line", !exists(chip("Season 7 · 7/10")))
        remote.press(KeyEvent.KEYCODE_DPAD_UP)
        remote.waitForFocus("Season 7 · 7/10")
        settle()
        assertEquals("no season but the 7th asked for", listOf(7), wideLoads.toList())
        assertTrue("season 7's episodes still shown", exists(row(wideName(7, 1))))
    }

    /**
     * Down from the chips enters at the next-up episode each time (S07E08, past the list's first screen), the list
     * walked back to its top in between.
     */
    @Test
    fun downFromTheChipsEntersAtTheNextUpEpisodeAgainAfterTheListWasWalkedToItsTop() {
        watchedWide(7, 7)
        openWide(7, "Play S07E08")
        focus(row(wideName(7, 7)), wideName(7, 7))
        val first = remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, wideName(7, 1), max = 8)
        assertTrue("never reached S07E01; at $first", wideName(7, 1) in first)
        assertEquals("Season 7 · 7/10", remote.press(KeyEvent.KEYCODE_DPAD_UP))
        remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.waitForFocus(wideName(7, 8))
    }

    /** Up to date, the selected season the last of nine, its chip past the end of the line: the first focus is on it. */
    @Test
    fun upToDateTheFirstFocusIsOnTheSelectedChipPastTheEndOfTheChipsLine() {
        watchedWide()
        openWide(9, "S9 ✓")
    }

    /**
     * Play focused on the show's last episode left, which is then watched (as the player closes on its end): the button
     * goes, the show up to date, and the focus with it to the selected season's chip, not the page's first button.
     */
    @Test
    fun theNextUpButtonGoneUnderTheFocusHandsItToTheSelectedChip() {
        runBlocking { container.library.markEpisodes((1..3).flatMap { s -> (1..3).map { ref(s, it) } }.dropLast(1), watched = true) }
        compose.setTvPage { ShowScreen(SHOW, onBack = {}) }
        waitFor(row(NAMES.getValue(3)[0]), 15_000)
        remote.waitForFocus("Play S03E03")
        settle()
        runBlocking { container.library.markEpisodes(listOf(ref(3, 3)), watched = true) }
        waitFor(hasText("Up to date", substring = true))
        remote.waitForFocus("S3 ✓")
    }

    /** Left and Right between the panes: each time to the element last focused in the pane. */
    @Test
    fun leftAndRightGoBackToWhereTheFocusWasInEachPane() {
        openPage()
        assertEquals("Choose release", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        val third = NAMES.getValue(2)[2]
        focus(row(third), third)
        assertEquals("Left from the episodes", "Choose release", remote.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue("Right from the left pane: $third", third in remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue("Up to S02E02", NAMES.getValue(2)[1] in remote.press(KeyEvent.KEYCODE_DPAD_UP))
        remote.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("and back: S02E02", NAMES.getValue(2)[1] in remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun rightFromAnEpisodeReachesItsMoreButtonWhoseChooseReleaseOpensTheChooserAndBackGivesTheFocusBack() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", fakeIndexerReachable())
        indexers.use("Fake (TV show test)", "http://10.0.2.2:8089")
        openPage()
        val third = NAMES.getValue(2)[2]
        focus(row(third), third)
        assertEquals("Right from the episode", "More for episode 3", remote.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Mark watched"))
        // The menu's first item, not the left pane's button of the same name.
        remote.waitForFocus("Choose release")
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Its list once the fake indexer has answered (with nothing for the show).
        waitFor(hasText("Size") and hasClickAction(), 30_000)
        settle()
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(row(third))
        remote.waitForFocus("More for episode 3")
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    /**
     * OK held on an episode: its menu, without a play; the OK still held there (its repeats, then its up, a while after
     * the menu showed) picks nothing in it.
     */
    @Test
    fun aLongOkOnAnEpisodeOpensItsMenuWithoutPlaying() {
        openPage()
        val first = NAMES.getValue(2)[0]
        focus(row(first), first)
        longOk(compose.mainClock::advanceTimeBy) {
            // A watched episode's menu.
            waitFor(hasText("Mark unwatched"))
            remote.waitForFocus("Choose release")
            Thread.sleep(500)
        }
        settle()
        assertTrue("no chooser opened", !exists(hasText("Finding releases…")) && !exists(hasText("Size") and hasClickAction()))
        assertTrue("the menu still shows", exists(hasText("Mark unwatched")))
        assertEquals(PlayState.Idle, container.playback.state.value)
        remote.press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !exists(hasText("Mark unwatched")) }
        remote.waitForFocus(first)
    }

    @Test
    fun chooseReleaseOnTheLeftPaneIsForTheNextUpEpisodeAndBackGivesItTheFocus() {
        assumeTrue("fake indexer not reachable at 10.0.2.2:8089 (mock-seed indexer)", fakeIndexerReachable())
        indexers.use("Fake (TV show test)", "http://10.0.2.2:8089")
        openPage()
        settle()
        assertEquals("Down from Resume", "Choose release", remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("S02E02", substring = true))
        waitFor(hasText("Size") and hasClickAction(), 30_000)
        settle()
        remote.press(KeyEvent.KEYCODE_BACK)
        waitFor(hasText(RESUME, substring = true))
        remote.waitForFocus("Choose release")
    }

    /** "Finding releases…" from an indexer that never answers: Cancel has the focus, and closes it back to the opener. */
    @Test
    fun cancelWhileFindingReleasesGoesBackToWhatOpenedTheChooser() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV show test)", silent.url)
            openPage()
            settle()
            remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText("Finding releases…"))
            remote.waitForFocus(CANCEL)
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(hasText(RESUME, substring = true))
            remote.waitForFocus("Choose release")
        }
    }

    /**
     * A season loading (S3, held until the test lets it answer): its outline's rows are where its rows come, as tall, and
     * the chips' line is as tall as with Mark season at its end, which shows only once the season has: nothing moves as
     * it loads.
     */
    @Test
    fun aSeasonLoadingKeepsTheChipsLineAndItsRowsWhereTheyAre() {
        val answers = TmdbLookup.seasonAged
        val held = CompletableDeferred<Unit>()
        TmdbLookup.seasonAged = { c, id, n ->
            if (id == SHOW && n == 3) held.await()
            answers(c, id, n)
        }
        try {
            openPage()
            val dp = compose.tvScreen().width / TV_SIZE.width.value
            fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().positionInRoot.y / dp
            val loaded = top(chip("S1 ✓"))
            focus(chip("Season 2 · 1/3"), "Season 2")
            remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            val outline = SemanticsMatcher.keyIsDefined(SkeletonRowKey)
            waitFor(outline, 5_000)
            settle()
            val loading = top(chip("S1 ✓"))
            val outlines = compose.onAllNodes(outline).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.positionInRoot.y / dp to it.size.height / dp }
            held.complete(Unit)
            waitFor(row(NAMES.getValue(3)[0]))
            settle()
            // A row's playing part, which the remote focuses, is inset 6 dp in its row.
            val rows = NAMES.getValue(3).map { name ->
                compose.onNode(row(name)).fetchSemanticsNode().let { it.positionInRoot.y / dp - 6 to it.size.height / dp + 12 }
            }
            val problems = mutableListOf<String>()
            if (abs(loading - loaded) > 1) problems += "the chips' line: S1 ✓ at %.1f dp loading, %.1f loaded".format(loading, loaded)
            rows.forEachIndexed { i, (rowTop, tall) ->
                val (outlineTop, outlineTall) = outlines[i]
                if (abs(rowTop - outlineTop) > 1 || abs(tall - outlineTall) > 1) {
                    problems += "row ${i + 1} at %.1f dp, %.1f dp tall; its outline at %.1f, %.1f tall".format(rowTop, tall, outlineTop, outlineTall)
                }
            }
            assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        } finally {
            held.complete(Unit)
            TmdbLookup.seasonAged = answers
        }
    }

    /** The next-up episode first in its season, at the top of the list: its tint keeps inside the card's rounded corners. */
    @Test
    fun theNextUpRowsTintKeepsInsideTheCardsRoundedCorners() {
        runBlocking { container.library.markEpisodes((1..3).map { ref(1, it) }, watched = true) }
        compose.setTvPage { ShowScreen(SHOW, onBack = {}) }
        waitFor(row(NAMES.getValue(2)[0]), 15_000)
        remote.waitForFocus("Play S02E01")
        settle()
        // The episodes' list, the card's size; the left pane scrolls too.
        val card = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
            .maxBy { it.boundsInRoot.left }.boundsInRoot
        val image = compose.onRoot().captureToImage().toPixelMap()
        val colors = AppColors()
        fun Color.near(other: Color) = abs(red - other.red) < 0.03f && abs(green - other.green) < 0.03f && abs(blue - other.blue) < 0.03f
        listOf(card.left.toInt() + 1 to card.top.toInt() + 1, card.right.toInt() - 2 to card.top.toInt() + 1).forEach { (x, y) ->
            val pixel = image[x, y]
            assertTrue("the card's top corner at $x,$y shows the page, not the tint: $pixel", pixel.near(colors.background) && !pixel.near(colors.rowSelected))
        }
    }

    /**
     * A season TMDB can't answer for: why, and Try again, which the focus doesn't jump to from the chips; pressed and
     * failing again, the focus stays on it; pressed once TMDB answers, the focus goes into the season's episodes.
     */
    @Test
    fun aSeasonThatCantBeLoadedSaysSoWithTryAgain() {
        failSeason3 = true
        openPage()
        focus(chip("Season 2 · 1/3"), "Season 2")
        remote.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor(hasText(SEASON_LOAD_FAILED, substring = true), 5_000)
        settle()
        assertEquals("the focus stays on the chip, its season selected (its words the same: not begun)", "S3", remote.focused())
        assertEquals("Down", TRY_AGAIN, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        assertEquals("failed again: the focus on Try again", TRY_AGAIN, remote.focused())
        failSeason3 = false
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(row(NAMES.getValue(3)[0]))
        remote.waitForFocus(NAMES.getValue(3)[0])
    }

    /** No details from TMDB: why, and Try again, focused; pressed once TMDB answers, the page. */
    @Test
    fun aShowTmdbCantAnswerForSaysSoWithTryAgain() {
        val fake = TmdbLookup.showAged
        var fail = true
        TmdbLookup.showAged = { c, id -> if (fail && id == SHOW) throw TmdbException(TmdbError.Network("Unable to resolve host")) else fake(c, id) }
        try {
            watched()
            compose.setTvPage { ShowScreen(SHOW, onBack = {}) }
            waitFor(hasText(SHOW_LOAD_FAILED, substring = true), 15_000)
            remote.waitForFocus(TRY_AGAIN)
            settle()
            fail = false
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(row(NAMES.getValue(2)[0]), 15_000)
            remote.waitForFocus(RESUME)
        } finally {
            TmdbLookup.showAged = fake
        }
    }

    @Test
    fun thePanesKeepInsideTheSafeAreaWithEveryTextShownInFullAt1xAnd13x() {
        val problems = mutableListOf<String>()
        listOf(1f, 1.3f).forEach { s ->
            scale = s
            if (s == 1f) openPage() else compose.waitForIdle()
            settle()
            compose.outsideTheSafeArea().forEach { problems += "${s}x: outside the safe area: $it" }
            compose.textsShownInFull().filterValues { !it }.keys.forEach { problems += "${s}x: not shown in full: \"$it\"" }
            compose.textsCut(::madeToEllipsize).forEach { problems += "${s}x: cut: $it" }
        }
        assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        // The panes: the left 48..348 dp, the right from 372 dp to the safe edge, 912.
        scale = 1f
        settle()
        val screen = compose.tvScreen()
        val dp = screen.width / TV_SIZE.width.value
        val resume = compose.onNode(hasText(RESUME, substring = true) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val mark = compose.onNode(hasText("Mark season watched") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val chip = compose.onNode(chip("S1 ✓")).fetchSemanticsNode().boundsInRoot
        fun near(px: Float, dpWanted: Int, slack: Int = 2) = abs((px - screen.left) / dp - dpWanted) <= slack
        assertTrue("Resume spans the left pane, 48..348 dp: %.0f..%.0f".format((resume.left - screen.left) / dp, (resume.right - screen.left) / dp), near(resume.left, 48) && near(resume.right, 348))
        assertTrue("the chips start in the right pane, at 372 dp and the chips' room: %.0f".format((chip.left - screen.left) / dp), near(chip.left, 378))
        assertTrue("Mark season ends at the safe edge, 912 dp: %.0f".format((mark.right - screen.left) / dp), near(mark.right, 912))
    }

    /**
     * A long title at 1x and 1.3x, and the play bar under the page: the title ends in an ellipsis past its lines, the
     * overview shows in whole lines or not at all, nothing else is cut, and the left pane's buttons keep their 48 dp.
     * The pane scrolls when it can't hold everything (1.3x with the bar): there only nothing cut counts.
     */
    @Test
    fun aLongTitleAndThePlayBarLeaveTheLeftPaneWhole() {
        SilentIndexer().use { silent ->
            indexers.use("Silent (TV show test)", silent.url)
            val problems = mutableListOf<String>()
            fun check(where: String, inFull: Boolean = true) {
                compose.outsideTheSafeArea().forEach { problems += "$where: outside the safe area: $it" }
                if (inFull) compose.textsShownInFull().filterValues { !it }.keys.forEach { problems += "$where: not shown in full: \"$it\"" }
                compose.textsCut { madeToEllipsize(it) || it == LONG_NAME }.forEach { problems += "$where: cut: $it" }
                val dp = compose.tvScreen().width / TV_SIZE.width.value
                listOf("Play S01E01", "Choose release", "+ Collection").forEach { b ->
                    val tall = compose.onNode(hasText(b, substring = true) and hasClickAction()).fetchSemanticsNode().size.height / dp
                    if (tall < 47.5f) problems += "$where: \"$b\" is %.1f dp tall".format(tall)
                }
            }
            listOf(1f, 1.3f).forEach { s ->
                scale = s
                if (s == 1f) {
                    compose.setTvPage({ scale }) { ShowScreen(LONG, onBack = {}) }
                    waitFor(row(NAMES.getValue(1)[0]), 15_000)
                    remote.waitForFocus("Play S01E01")
                }
                settle()
                check("${s}x")
            }
            // The play bar: Play searching an indexer that never answers (or saying why it can't play), its sheet hidden.
            scale = 1f
            settle()
            remote.waitForFocus("Play S01E01")
            remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { container.playback.state.value != PlayState.Idle }
            compose.runOnIdle { container.playback.minimize() }
            // The bar under the page, the title in its words.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(LONG_NAME, substring = true)).fetchSemanticsNodes().any { it.words() != LONG_NAME } }
            settle()
            check("1x with the play bar")
            scale = 1.3f
            settle()
            check("1.3x with the play bar", inFull = false)
            scale = 1f
            assertEquals("problems:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
        }
    }

    /** The texts the page ends in an ellipsis when there is no room for them whole: the overview, an episode's name. */
    private fun madeToEllipsize(words: String) = words == show.overview || NAMES.values.flatten().any { " · $it" in words }

    /** The second press of a double OK that opened the page, with Resume focused: no play. */
    @Test
    fun theOkThatOpenedThePagePlaysNothing() {
        val opened = openPage()
        earlyOk(opened)
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals(PlayState.Idle, container.playback.state.value)
    }

    private companion object {
        /** An invented show; nothing real has the id. */
        const val SHOW = 900071
        const val NAME = "Saltmarsh Light"
        /** The same show by a long name: an invented id and title. */
        const val LONG = 900072
        const val LONG_NAME = "The Lantern Keepers of Saltmarsh"
        /** A show of nine seasons: an invented id and title. */
        const val WIDE = 900073
        const val WIDE_NAME = "Ninefold Harbour"
        fun wideCount(season: Int) = if (season == 7) 10 else 3
        fun wideName(season: Int, episode: Int) = "Tidemark $season.%02d".format(episode)
        const val INDEXER = "tv-show-screen-test"
        const val RESUME_MS = 11 * 60_000L + 15_000L
        const val RESUME = "Resume S02E02 · 11:15"
        val NAMES = mapOf(
            1 to listOf("Reed Bank", "Tidewrack", "Heron Watch"),
            2 to listOf("Low Lantern", "The Long Tide", "Brackwater"),
            3 to listOf("Fen Bell", "Salt Hollow", "Last Ferry"),
            0 to listOf("The Keeper's Log"),
        )
    }
}

package io.skjaere.usenet.app.download

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.AppNav
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.collection.MovieScreen
import io.skjaere.usenet.app.collection.SHOW_FOOTNOTE_PHONE
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.CC
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.GB
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.QM
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.film
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.found
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.search.MovieHeroCard
import io.skjaere.usenet.app.search.TitleInfo
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FetchException
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The ways into downloads on a phone (item 18; L356, L369, L370, L379, L380, L384), over the app's own runner and storage,
 * the TMDB seam answering for the invented Harbor Lights and Copper Crown, rows seeded through the app's storage:
 * - Harbor Lights' Season 2 rows say their downloads, as drawn: a line under the date, TalkBack's state, an 18 dp mark.
 * - Each row's ⋮ offers the download's items by state; Download opens the Download sheet, Pause holds the queue, and
 *   Remove download hides it with the Undo bar, whose Undo brings it back.
 * - At twice the font size the marks keep their size and the lines wrap whole.
 * - The Movie page's Download follows its download through its states, its menu by state, Remove and Undo.
 * - Search's movie card: Download beside N releases, one under the other at twice the font size.
 * - Home's Downloads section, shown only while something is queued or downloaded; See all opens the Downloads page, a
 *   show's row its downloads.
 *
 * The app's Usenet indexers are switched off meanwhile, and a grab throws, so nothing reaches an indexer: the sheet's
 * search finds nothing to ask. The settings are put back as they were, the rows and their folders deleted, and the
 * downloads' jobs cancelled. Titles, episodes and releases are invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadEntriesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val seeds = DownloadsPageSeeds(context, container)
    private val home = HomeContentShown()
    private val realSource = PlaybackCoordinator.nzbSource
    private val grabs = AtomicInteger()
    private var tmdb: FakeTmdb? = null
    private lateinit var saved: AppSettings

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never show on a TV (L369)", container.television)
        // The debug build's start saves its dev settings: before the test reads or takes any.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        assumeTrue("no Usenet provider saved and on, so no Download is offered (L370)", container.settings.usableProviders().isNotEmpty())
        saved = container.settings.settings.first()
        container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
        PlaybackCoordinator.nzbSource = { _, _ -> NzbSource { grabs.incrementAndGet(); throw FetchException("Not to be fetched", stopsAll = true) } }
        // Strict: what it doesn't know is refused, never sent to TMDB (Home asks after what else the library keeps).
        tmdb = FakeTmdb(shows = mapOf(HL to SHOW), seasons = { id, number -> SEASON.takeIf { id == HL && number == 2 } }, movies = mapOf(CC to CROWN), strict = true)
        seeds.before()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        try {
            tmdb?.close()
        } catch (e: AssertionError) {
            // What Home asked of the library's other titles was refused, never sent: the pages here ask only for their own.
        }
        PlaybackCoordinator.nzbSource = realSource
        container.playback.cancel()
        seeds.after()
        if (::saved.isInitialized) container.settings.restore(saved)
        home.restore()
        assertEquals("indexer grabs", 0, grabs.get())
    }

    /** Season 2 as drawn: 1 not downloaded, 2 to 4 downloaded (3 with parts missing), 5 downloading, 6 queued, 7 paused, 8 failed. */
    private fun seedSeason(): Map<Int, Download> = mapOf(
        2 to seeds.seed(episode(2, 2, DownloadState.Done, size = 1_300_000_000, position = 2)),
        3 to seeds.seed(episode(2, 3, DownloadState.Done, size = 1_400_000_000, gaps = 2, position = 3)),
        4 to seeds.seed(episode(2, 4, DownloadState.Done, size = 1_400_000_000, position = 4)),
        5 to seeds.seed(episode(2, 5, DownloadState.Running, size = GB, file = true, written = 420_000_000, position = 5)),
        6 to seeds.seed(episode(2, 6, DownloadState.Queued, position = 6)),
        7 to seeds.seed(episode(2, 7, DownloadState.Paused, size = GB, file = true, written = 180_000_000, position = 7)),
        8 to seeds.seed(episode(2, 8, DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c"), position = 8)),
    )

    /**
     * Scrolls whichever list has [matcher] to it (the Show page has the season chips' row beside its own), and says whether
     * it is there.
     */
    private fun scrolledTo(matcher: SemanticsMatcher): Boolean {
        compose.waitForIdle()
        if (compose.exists(matcher)) {
            // Composed, as the season's rows are in one card, but maybe out of sight.
            runCatching { compose.onAllNodes(matcher)[0].performScrollTo() }
            return true
        }
        compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            try {
                compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(matcher)
            } catch (e: AssertionError) {
                // Not in this list.
            }
            if (compose.exists(matcher)) return true
        }
        return compose.exists(matcher)
    }

    /** A menu's items now showing: what is in a popup, by its words. */
    private fun menuWords(): List<String> = MENU_WORDS.filter { compose.exists(hasText(it) and hasClickAction() and hasAnyAncestor(isPopup())) }

    /** An episode's row (not its ⋮), by its name. */
    private fun row(number: Int) = hasText(NAMES.getValue(number)) and hasClickAction()

    private fun rowNode(number: Int): SemanticsNode {
        scrolledTo(row(number))
        return compose.onNode(row(number)).fetchSemanticsNode()
    }

    /** The marks drawn, in the unmerged tree: which each is and its size in dp. */
    private fun marks(): List<Pair<DownloadMark, Float>> {
        val density = context.resources.displayMetrics.density
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(DownloadMarkLook), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[DownloadMarkLook] to it.size.width / density }
    }

    private fun showPage(fontScale: Float = 1f) {
        compose.show(context, fontScale) { ShowScreen(HL, onBack = {}) }
        compose.waitFor(row(8), 15_000)
    }

    @Test
    fun episodeRowsSayTheirDownloadsInALineAMarkAndTalkBacksState() {
        seedSeason()
        showPage()
        val lines = mapOf(
            2 to "Downloaded · 1.3 GB", 3 to "Downloaded · 2 parts missing", 4 to "Downloaded · 1.4 GB", 5 to "Downloading · 42%",
            6 to "Queued for download", 7 to "Paused · 18%", 8 to "Download failed",
        )
        val spoken = mapOf(
            2 to "Downloaded", 3 to "Downloaded, 2 parts missing", 4 to "Downloaded", 5 to "Downloading, 42 percent", 6 to "Queued for download",
            7 to "Download paused, 18 percent", 8 to "Download failed",
        )
        lines.forEach { (n, line) ->
            compose.waitTill("episode $n's line '$line'") { scrolledTo(row(n)) && drawn(line) }
            assertEquals("episode $n's state", spoken.getValue(n), rowNode(n).config.getOrNull(SemanticsProperties.StateDescription))
            // TalkBack hears it once, as the row's state: the line drawn says nothing of its own in the row's words.
            assertTrue("episode $n's words repeat its state: ${rowNode(n).texts()}", rowNode(n).texts().none { it == line })
        }
        // Episode 1 has none: its row is as it was.
        assertEquals(null, rowNode(1).config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(rowNode(1).texts().none { it.startsWith("Download") || it.startsWith("Queued") })
        // The marks as drawn, each 18 dp: the tick in the tray, a ring, the arrow, ⚠; never the watched ✓'s green.
        scrolledTo(row(8))
        val drawn = marks().map { it.first }
        assertTrue("marks drawn: $drawn", DownloadMark.Failed in drawn && DownloadMark.Queued in drawn && DownloadMark.Progress(0.18f, held = true) in drawn)
        scrolledTo(row(2))
        assertTrue("marks drawn: ${marks()}", marks().map { it.first }.containsAll(listOf(DownloadMark.Done, DownloadMark.Progress(0.42f, held = false))))
        marks().forEach { (mark, dp) -> assertTrue("$mark is $dp dp", abs(dp - 18f) < 0.5f) }
        // The footnote says the ⋮ downloads.
        assertTrue(scrolledTo(hasText(SHOW_FOOTNOTE_PHONE)))
    }

    /** Opens episode [number]'s ⋮ and returns its items' words, in order. */
    private fun menuOf(number: Int): List<String> {
        val more = hasContentDescription("More for episode $number")
        scrolledTo(more)
        compose.onNode(more).performClick()
        try {
            compose.waitFor(hasText("Choose release") and hasClickAction() and hasAnyAncestor(isPopup()))
        } catch (e: AssertionError) {
            throw AssertionError("episode $number's menu never opened: " + compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }, e)
        }
        return menuWords()
    }

    private fun closeMenu() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitTill("the menu to close") { menuWords().isEmpty() }
    }

    private fun pick(words: String) {
        val inMenu = hasText(words) and hasClickAction() and hasAnyAncestor(isPopup())
        compose.onNode(if (compose.exists(inMenu)) inMenu else hasText(words) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun eachRowsMenuOffersItsDownloadByStateAndItsItemsDoWhatTheySay() {
        val rows = seedSeason()
        showPage()
        val expected = mapOf(
            1 to listOf("Download"),
            2 to listOf("Remove download"),
            5 to listOf("Pause download", "Remove download"),
            6 to listOf("Remove download"),
            7 to listOf("Resume download", "Remove download"),
            8 to listOf("Download again", "Remove download"),
        )
        expected.forEach { (n, items) ->
            assertEquals("episode $n's menu", listOf("Choose release", "Mark watched") + items, menuOf(n))
            closeMenu()
        }

        // Download: the Download sheet for the episode, over the page; its search has no indexer to ask.
        menuOf(1)
        pick("Download")
        compose.waitFor(hasText("Harbor Lights · S02E01 · Brine"))
        compose.waitTill("the sheet to find nothing", 20_000) { compose.exists(hasText(DownloadSheetModel.NO_RELEASE)) }
        pick("Close")
        compose.waitTill("the sheet to close") { !compose.exists(hasText("Harbor Lights · S02E01 · Brine")) }

        // Pause: the running one is held where it is (L380).
        menuOf(5)
        pick("Pause download")
        compose.waitTill("episode 5 paused") { drawn("Paused · 42%") && state(5) == "Download paused, 42 percent" }
        assertEquals(DownloadState.Paused, seeds.row(rows.getValue(5).id)?.state)

        // Remove: hidden at once, with the Undo bar; Undo brings it back as it was (L379).
        menuOf(2)
        pick("Remove download")
        compose.waitFor(hasText("Deleted Harbor Lights S02E02 · 1.3 GB"))
        compose.waitTill("episode 2's download hidden") { !drawn("Downloaded · 1.3 GB") && state(2) == null }
        assertEquals(DownloadState.Deleting, seeds.row(rows.getValue(2).id)?.state)
        pick("Undo")
        compose.waitTill("episode 2's download back") { drawn("Downloaded · 1.3 GB") && state(2) == "Downloaded" }
        assertEquals(DownloadState.Done, seeds.row(rows.getValue(2).id)?.state)
    }

    @Test
    fun atTwiceTheTextTheMarksKeepTheirSizeAndTheLinesWrapWhole() {
        seedSeason()
        showPage(fontScale = 2f)
        val width = context.resources.displayMetrics.widthPixels
        listOf(3 to "Downloaded · 2 parts missing", 5 to "Downloading · 42%", 7 to "Paused · 18%", 8 to "Download failed").forEach { (n, line) ->
            scrolledTo(row(n))
            compose.assertWhole(line, width)
            marks().forEach { (mark, dp) -> assertTrue("$mark is $dp dp at 2x", abs(dp - 18f) < 0.5f) }
        }
        assertTrue("no mark drawn at 2x", marks().isNotEmpty())
    }

    /** The Movie page's Download button's words, as one node reads them. */
    private fun buttonSays(vararg words: String) =
        compose.waitTill("the Download button saying ${words.toList()}") {
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().any { node -> node.texts() == words.toList() }
        }

    private fun pressButton(first: String) {
        compose.onNode(hasText(first) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theMoviePagesDownloadFollowsItsDownloadItsMenuByStateRemoveAndUndo() = runBlocking<Unit> {
        var openedDownloads = 0
        compose.show(context) { MovieScreen(CC, onBack = {}, onDownloads = { openedDownloads++ }) }
        compose.waitFor(hasText("Choose release") and hasClickAction(), 15_000)
        // Not downloaded: Download, a third button under Choose release.
        buttonSays("Download")

        val dao = container.data.downloads
        val row = seeds.seed(film(DownloadState.Queued).copy(size = null, relativePath = null, safeOffset = 0, completedAt = null))
        buttonSays("Queued for download")
        pressButton("Queued for download")
        assertEquals(listOf("Remove download", "Open Downloads"), menuWords())
        pick("Open Downloads")
        assertEquals(1, openedDownloads)

        val states = listOf(
            row.copy(state = DownloadState.Resolving, reason = DownloadHostRules.FINDING_RELEASES) to listOf("Finding releases…"),
            row.copy(state = DownloadState.Running, size = 5_500_000_000, relativePath = "downloads/${row.id}/v.mkv", safeOffset = 2_310_000_000) to
                listOf("Downloading", "42% of 5.5 GB"),
            row.copy(state = DownloadState.Waiting, reason = DownloadHostRules.WAITING_FOR_WIFI) to listOf("Queued for download", "Waiting for Wi-Fi"),
            row.copy(state = DownloadState.Paused, size = 5_500_000_000, relativePath = "downloads/${row.id}/v.mkv", safeOffset = 2_310_000_000) to
                listOf("Paused", "42% of 5.5 GB"),
            row.copy(state = DownloadState.Failed, reason = DownloadResolver.INCOMPLETE, tried = listOf("a", "b", "c")) to listOf("Download failed", "3 releases tried"),
            film(gaps = 2).copy(id = row.id, position = row.position, relativePath = "downloads/${row.id}/v.mkv") to listOf("Downloaded", "2 parts missing"),
            film().copy(id = row.id, position = row.position, relativePath = "downloads/${row.id}/v.mkv") to listOf("Downloaded", "5.5 GB"),
        )
        val menus = mapOf(
            "Downloading" to listOf("Pause download", "Remove download", "Open Downloads"),
            "Paused" to listOf("Resume download", "Remove download", "Open Downloads"),
            "Download failed" to listOf("Download again", "Remove download", "Open Downloads"),
            "Downloaded" to listOf("Remove download", "Open Downloads"),
        )
        states.forEach { (state, words) ->
            dao.update(state)
            buttonSays(*words.toTypedArray())
            menus[words.first()]?.let { items ->
                pressButton(words.first())
                assertEquals("${words.first()}'s menu", items, menuWords())
                closeMenu()
            }
        }

        // Remove: no question, the Undo bar; the button offers Download again until Undo brings it back (L379).
        pressButton("Downloaded")
        pick("Remove download")
        compose.waitFor(hasText("Deleted Copper Crown · 5.5 GB"))
        buttonSays("Download")
        assertEquals(DownloadState.Deleting, seeds.row(row.id)?.state)
        pick("Undo")
        buttonSays("Downloaded", "5.5 GB")
        assertEquals(DownloadState.Done, seeds.row(row.id)?.state)
    }

    @Test
    fun searchsMovieCardHasDownloadBesideItsReleasesOneUnderTheOtherAtTwiceTheText() {
        val sheets = CopyOnWriteArrayList<DownloadRequest>()
        var scale by mutableFloatStateOf(1f)
        val actions = TitleDownloadActions(TitleDownloadActions.Deps.of(container), container.appScope, container.appScope)
        val target = MovieTarget(CC, "Copper Crown", 2022, null, 112)
        compose.setContent {
            val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    MovieHeroCard(
                        CARD, showArtwork = false, GROUP, inCollection = false, adding = false, onPlay = {}, onReleases = {}, onAdd = {}, onOpen = {}, onJump = {},
                        download = movieDownloadSlot(target, null, actions, onSheet = { sheets += it }, onDownloads = {}),
                    )
                }
            }
        }
        val download = hasText("Download") and hasClickAction()
        val releases = hasText("3 releases") and hasClickAction()
        compose.waitFor(download)
        // Play on a row of its own; Download and 3 releases side by side under it, in halves.
        val play = compose.onNode(hasText("Play") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        var d = compose.onNode(download).fetchSemanticsNode().boundsInRoot
        var r = compose.onNode(releases).fetchSemanticsNode().boundsInRoot
        assertTrue("Download $d under Play $play", d.top >= play.bottom)
        assertTrue("side by side: $d, $r", abs(d.center.y - r.center.y) < 2f && d.right <= r.left)
        assertTrue("in halves: $d, $r", abs(d.width - r.width) < 2f)
        // A press opens the film's sheet.
        compose.onNode(download).performClick()
        compose.waitTill("the sheet asked for") { sheets.isNotEmpty() }
        assertEquals(DownloadRequest.Movie(target, null), sheets.single())

        // At twice the text, one under the other, each the card's width, nothing cut off.
        scale = 2f
        compose.waitForIdle()
        d = compose.onNode(download).fetchSemanticsNode().boundsInRoot
        r = compose.onNode(releases).fetchSemanticsNode().boundsInRoot
        assertTrue("one under the other at 2x: $d, $r", d.bottom <= r.top && abs(d.left - r.left) < 2f && abs(d.width - r.width) < 2f)
        val width = context.resources.displayMetrics.widthPixels
        compose.assertWhole("Download", width, hasText("Download"))
        compose.assertWhole("3 releases", width)

        // Downloading: the button grows to its two lines, whole.
        seeds.seed(film(DownloadState.Running).copy(safeOffset = 2_310_000_000, completedAt = null))
        compose.waitFor(hasText("42% of 5.5 GB"))
        compose.assertWhole("Downloading", width, hasText("Downloading"))
        compose.assertWhole("42% of 5.5 GB", width)
    }

    @Test
    fun homesDownloadsShowOnlyWithSomethingQueuedOrDownloadedAndOpenWhatTheyName() = runBlocking<Unit> {
        home.show()
        lateinit var nav: NavHostController
        compose.show(context) {
            nav = rememberNavController()
            AppNav(nav = nav)
        }
        // Section headers are said in capitals.
        val header = hasText("Downloads · ", substring = true, ignoreCase = true)
        compose.waitFor(hasText("Search your indexers"), 15_000)
        compose.waitForIdle()
        assertFalse("a Downloads section with nothing downloaded", scrolledTo(header))

        seeds.seed(episode(2, 5, DownloadState.Running, size = 3_100_000_000, file = true, written = 1_302_000_000, position = 1))
        seeds.seed(film().copy(completedAt = 50))
        seeds.seed(episode(1, 1, DownloadState.Done, show = QM, title = "Quiet Meridian", size = 1_800_000_000).copy(completedAt = 30))
        seeds.seed(episode(1, 2, DownloadState.Done, show = QM, title = "Quiet Meridian", size = 1_800_000_000).copy(completedAt = 40))
        // What downloads hold: 3.1 GB of the one being written, whole, 5.5 GB and 3.6 GB.
        compose.waitTill("Home's Downloads section", 15_000) { scrolledTo(hasText("Downloads · 12.2 GB", ignoreCase = true)) }
        listOf("S02E05 · downloading 42%", "Downloaded · 5.5 GB", "2 episodes · 3.6 GB").forEach { assertTrue("'$it' on Home", scrolledTo(hasText(it))) }

        // See all: the Downloads page.
        compose.onAllNodes(hasText("See all") and hasClickAction())[0].performClick()
        compose.waitTill("the Downloads page") { nav.currentDestination?.hasRoute(DownloadsRoute::class) == true }
        compose.waitFor(hasText(DownloadsPage.DOWNLOADING, ignoreCase = true))
        compose.runOnIdle { nav.popBackStack() }

        // A show's row: its downloads.
        compose.waitTill("Home again") { scrolledTo(hasText("2 episodes · 3.6 GB")) }
        compose.onAllNodes(hasText("Quiet Meridian") and hasClickAction())[0].performClick()
        compose.waitTill("Quiet Meridian's downloads") { nav.currentDestination?.hasRoute(DownloadsShowRoute::class) == true }
        compose.waitFor(hasText("Quiet Meridian"))
    }

    /** A node's words, merged: its texts, as TalkBack would read them in order. */
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** Whether [line] is drawn: a row's download line, silent itself, so found among the words as laid out, not as heard. */
    private fun drawn(line: String): Boolean = compose.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** What TalkBack hears as episode [number]'s state. */
    private fun state(number: Int): String? = rowNode(number).config.getOrNull(SemanticsProperties.StateDescription)

    private companion object {
        const val SEEDING_MILLIS = 60_000L

        val NAMES = DownloadsPageSeeds.NAMES.filterKeys { it.first == 2 }.mapKeys { it.key.second } + mapOf(7 to "Slack Water", 8 to "Undertow")

        /** Every item a download's menus can have. */
        val MENU_WORDS = listOf("Choose release", "Mark watched", "Download", "Pause download", "Resume download", "Download again", "Remove download", "Open Downloads")

        private val today: LocalDate = LocalDate.now()

        val SHOW = ShowDetails(
            HL, "Harbor Lights", null, 2021, null, null, null, "Returning Series", true, 50,
            listOf(SeasonSummary(2, null, 8, today.minusDays(90), null)),
            lastEpisode = EpisodeSummary(2, 8, "Undertow", today.minusDays(20)), nextEpisode = null, ids = ExternalIds(),
        )

        val SEASON = Season(HL, 2, null, null, (1..8).map { n -> Episode(2, n, NAMES.getValue(n), null, today.minusDays(90L - 7 * n), 50, null) })

        val CROWN = MovieDetails(
            CC, "Copper Crown", null, 2022, "A jeweller is hired to copy the crown she once stole.", null, null, 112, ExternalIds(),
            releaseDate = LocalDate.of(2022, 3, 1), status = "Released",
        )

        val CARD = TitleInfo(MediaType.Movie, CC, "Copper Crown", 2022, "Movie · 2022 · 1 h 52 min", null, null, null, TvIds(tmdb = CC), 112)

        val GROUP = ResultGroup(
            "movie:$CC", GroupKind.Movie, null, emptyList(), null, 2022,
            listOf("Copper.Crown.2022.1080p.BluRay.x264-KESTREL", "Copper.Crown.2022.1080p.WEB-DL.H.264-WREN", "Copper.Crown.2022.720p.WEB-DL.H.264-GRP")
                .mapIndexed { i, name -> found(name, 10_400_000_000 - i * 3_000_000_000L, 90.0 - i).let { RankedItem(it.item, it.ranked) } },
            emptyList(),
        )
    }
}

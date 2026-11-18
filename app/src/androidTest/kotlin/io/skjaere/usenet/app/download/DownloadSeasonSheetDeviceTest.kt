package io.skjaere.usenet.app.download

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.RankingContext
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.IndexerReport
import io.skjaere.usenet.search.SearchStage
import io.skjaere.usenet.search.SeasonFind
import io.skjaere.usenet.search.StageReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A season's Download sheet on a phone (W114, L396, L400, L433), opened by a test host as the Show page's season
 * Download opens it, over the app's own runner and storage, its search a seam with no indexer: invented releases for
 * an invented show's season 2, ranked as a search would rank them for downloading.
 * - Finding while the search runs, nothing queued; then the season: its line, those counted apart, each episode about
 *   how large (one failed before), the space free, when it starts, Download and Cancel, and no Choose release.
 * - Download queues each episode once, in order, at consecutive places, each with its releases and search facts, asks
 *   for its host and closes.
 * - Not all fitting: the first three, under "Download the first 3", the rest dimmed under the cut.
 * - Nothing found for the season: the search's line and Close alone.
 * - At twice the font size nothing is cut off: Finding, the season on mobile data, and none fitting, the sheet's content
 *   laid out at that size in the test's window (the sheet's own window takes the system's).
 *
 * The free space, the queue it reads, the network and its host's schedule are seams, so nothing grabs the invented
 * releases. The rows it queued are deleted after each case, their folders with them, and the downloads' jobs cancelled.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSeasonSheetDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val scheduled = AtomicInteger()
    private var closes = 0

    /** The font size the case shows the sheet at, which its words are laid out at. */
    private var fontScale = 1f

    /** The search answers once it is let go: at once, unless a case holds it. */
    private val answer = CompletableDeferred<Unit>()

    /** What each search was ranked by: the app's own download context ([DownloadSheetDeps.of]'s), which the seam takes. */
    private val rankedBy = CopyOnWriteArrayList<RankingContext>()

    @After
    fun tearDown() = runBlocking<Unit> {
        mine().forEach { container.downloadStorage.delete(it.id) }
        // None should be scheduled (the seam): the namespace's jobs go all the same, as every download test leaves it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    @Test
    fun findingOnlySearchesThenTheSeasonSaysEachEpisodeTheSpaceFreeAndWhenItStarts() = runBlocking<Unit> {
        show(SEASON, deps())
        waitFor(hasText("Finding releases for season 2…"))
        assertTrue(exists(hasText("Harbor Lights · Season 2")))
        assertTrue(exists(hasText(DownloadSeasonSheetModel.FINDING_NOTE)))
        assertTrue(exists(hasText("Cancel") and hasClickAction()))
        assertFalse("no Download while it finds", exists(downloadButton))
        assertEquals("nothing queued while it finds", emptyList<Download>(), mine())

        answer.complete(Unit)
        waitFor(hasText("Season 2 · 6 episodes · about 8.4 GB"))
        // Ranked for downloading (L355): the app's context for it, this device's, never Play's.
        assertEquals(listOf(RankingTarget.Download), rankedBy.map { it.target })
        listOf(
            "Harbor Lights", "1 and 2 are downloaded already, so they're left out.", "41.0 GB free on this device", DownloadSeasonSheetModel.SEASON_FOOT,
            "Starts now, one episode at a time",
        ).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        // Each episode read as it shows: its number, its name, what is said under it, about how large.
        assertEquals(listOf("3", "Fog Signal", "Failed before · searched afresh", "about 1.4 GB"), texts(row("Fog Signal")))
        assertEquals(listOf("4", "Low Water", "about 1.2 GB"), texts(row("Low Water")))
        assertEquals(listOf("8", "Undertow", "about 1.4 GB"), texts(row("Undertow")))
        val download = compose.onNode(hasText("Download 6 episodes") and hasClickAction()).fetchSemanticsNode()
        assertEquals(Role.Button, download.config.getOrNull(SemanticsProperties.Role))
        assertTrue(exists(hasText("Cancel") and hasClickAction()))
        assertFalse("no Choose release on a season's sheet", exists(hasText(DownloadSheetModel.CHOOSE_RELEASE)))
        assertEquals(0, scheduled.get())
    }

    @Test
    fun downloadQueuesEachEpisodeOnceInOrderWithItsReleasesAndSearchFactsAsksForItsHostAndCloses() = runBlocking<Unit> {
        answer.complete(Unit)
        show(SEASON, deps())
        tap("Download 6 episodes")
        waitClosed()
        val rows = mine().sortedBy { it.position }
        assertEquals((3..8).toList(), rows.map { it.episode })
        assertEquals("consecutive places", rows.indices.map { rows.first().position + it }, rows.map { it.position })
        assertTrue(rows.all { it.state == DownloadState.Queued })
        val third = rows.first()
        assertEquals(WatchKey.episode(SHOW_ID, 2, 3), third.watchKey)
        assertEquals("S02E03 · Fog Signal", third.subtitle)
        assertEquals(8, third.seasonEpisodes)
        assertEquals("the release first, then those that fit", listOf(single(3), single(3, "ATMOS")), third.candidates.map { it.title })
        assertEquals(1_400_000_000, third.indexerSize)
        // Each with its search facts, not searched yet, with which its turn searches (L392).
        rows.forEach { row -> assertEquals(SeasonSearch.of(play(row.episode!!).request), DownloadColumns.search(row)) }
        assertEquals("its host asked for once", 1, scheduled.get())
    }

    @Test
    fun aSeasonThatWontAllFitDownloadsTheFirstThatFitTheRestDimmedUnderTheCut() = runBlocking<Unit> {
        answer.complete(Unit)
        // 5.1 GB free: S02E03 to E05 come to 4.1 GB.
        show(SEASON, deps(space = DownloadSpace(available = 6_100_000_000, used = 0, queued = 0)))
        waitFor(hasText("Won't all fit: 5.1 GB free on this device"))
        listOf("The first 3 fit: about 4.1 GB.", "✕ No room for these 3", DownloadSheetModel.STORAGE_SETTINGS, "Cancel").forEach {
            assertTrue("'$it' shown", exists(hasText(it)))
        }
        // The rows after the cut read as they show: dimmed, as TalkBack says a greyed row; those that fit as they are.
        assertEquals(listOf("6", "Salt and Iron", "about 1.3 GB"), texts(row("Salt and Iron")))
        assertTrue("a row after the cut reads as dimmed", dimmed(row("Salt and Iron")))
        assertFalse("a row that fits reads as it is", dimmed(row("Fog Signal")))
        tap("Download the first 3")
        waitClosed()
        assertEquals(listOf(3, 4, 5), mine().sortedBy { it.position }.map { it.episode })
        assertEquals(1, scheduled.get())
    }

    @Test
    fun nothingFoundForTheSeasonSaysWhatItsSearchDidWithCloseAlone() = runBlocking<Unit> {
        answer.complete(Unit)
        show(SEASON, deps(found = emptyMap()))
        waitFor(hasText("No playable release found for season 2"))
        listOf(
            "Harbor Lights · Season 2", "Searched 2 indexers for season 2 by TVDB id", "38 results, none for season 2", DownloadSeasonSheetModel.NO_RELEASE_NOTE,
        ).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertFalse(exists(hasText("Cancel")))
        assertFalse(exists(downloadButton))
        tap("Close")
        waitClosed()
        assertEquals(emptyList<Download>(), mine())
    }

    @Test
    fun atTwiceTheFontSizeTheSeasonOnMobileDataIsWhole() = runBlocking<Unit> {
        showContent(SEASON, deps(metered = true), fontScale = 2f)
        waitFor(hasText("Finding releases for season 2…"))
        listOf("Harbor Lights · Season 2", "Finding releases for season 2…", DownloadSeasonSheetModel.FINDING_NOTE, "Cancel").forEach(::assertWhole)

        answer.complete(Unit)
        waitFor(hasText("Season 2 · 6 episodes · about 8.4 GB"))
        listOf(
            "Season 2 · 6 episodes · about 8.4 GB", "1 and 2 are downloaded already, so they're left out.", "Fog Signal", "Failed before · searched afresh",
            "The Long Tide", "Salt and Iron", "about 1.5 GB", "41.0 GB free on this device", DownloadSeasonSheetModel.SEASON_FOOT,
            "Starts on Wi-Fi, one episode at a time", "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.", "Cancel",
        ).forEach(::assertWhole)
        assertWholeButton("Download 6 episodes")
    }

    @Test
    fun atTwiceTheFontSizeASeasonNoneOfWhichFitsIsWhole() = runBlocking<Unit> {
        answer.complete(Unit)
        // 900 MB free, after the 1.0 GB kept free for the phone: S02E03's 1.4 GB doesn't fit.
        showContent(SEASON, deps(space = DownloadSpace(available = 1_900_000_000, used = 0, queued = 0)), fontScale = 2f)
        waitFor(hasText(DownloadSeasonSheetModel.WONT_FIT))
        listOf(
            "✕ No room for these 6", "Fog Signal", "Undertow", DownloadSeasonSheetModel.WONT_FIT, "The first needs about 1.4 GB · 900 MB free on this device",
            "That is after the 1.0 GB kept free for the phone.", DownloadSeasonSheetModel.NONE_FITS_NOTE, DownloadSheetModel.STORAGE_SETTINGS, "Cancel",
        ).forEach(::assertWhole)
        assertFalse("no Download where none fits", exists(downloadButton))
        // None fits: every row under the band reads as dimmed, the first among them.
        listOf("Fog Signal", "Undertow").forEach { assertTrue("'$it' reads as dimmed", dimmed(row(it))) }
    }

    /** Any Download button of the sheet's. */
    private val downloadButton = SemanticsMatcher("a Download button") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("Download ") }
    } and hasClickAction()

    /** The episode row named [name], as TalkBack reads it: one node of its words. */
    private fun row(name: String): SemanticsNode = compose.onAllNodes(hasText(name)).fetchSemanticsNodes().single()

    private fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** Whether TalkBack reads [node] as dimmed, as it reads a greyed control. */
    private fun dimmed(node: SemanticsNode): Boolean = SemanticsProperties.Disabled in node.config

    /**
     * The sheet's content for [request] over [deps], at [fontScale], in the test's own window: the sheet's window lays its
     * words out at the system's font size, whatever a page's density says, so a case at twice the size shows its content.
     */
    private fun showContent(request: SeasonDownloadRequest, deps: DownloadSheetDeps, fontScale: Float) {
        this.fontScale = fontScale
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    val scope = rememberCoroutineScope()
                    val model = remember { DownloadSeasonSheetModel(request, deps, scope) }
                    LaunchedEffect(model) { model.start() }
                    DownloadSeasonSheetContent(request, model, onClose = {})
                }
            }
        }
    }

    /** The sheet for [request] over the app's own runner, with [deps], as the Show page's season Download opens it. */
    private fun show(request: SeasonDownloadRequest, deps: DownloadSheetDeps) {
        var open by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                if (open) {
                    DownloadSeasonSheet(request, onClose = {
                        open = false
                        closes++
                    }, deps)
                }
            }
        }
    }

    /**
     * The app's deps, but for the seams: the season's search ([answer] lets it go) finding [found] by episode, the space
     * free (41.0 GB unless [space] says), the queue (nothing ahead), the settings (as a new install has them: Only on Wi-Fi
     * or Ethernet on), the network (Wi-Fi, or mobile data where [metered]) and the host's schedule, counted.
     */
    private fun deps(
        metered: Boolean = false,
        space: DownloadSpace = DownloadSpace(available = 42 * GB, used = 0, queued = 0),
        found: Map<Int, List<RankedItem>> = RELEASES,
    ) = DownloadSheetDeps.of(container, ask = null, context).copy(
        findSeason = { request, _, ranking ->
            rankedBy += ranking
            answer.await()
            SeasonFind(
                request.episodes.associate { e ->
                    e.number to FindResult(request.request(e), found[e.number].orEmpty().map { FoundRelease(it.item, it.ranked, SearchStage.SeasonPack) }, emptyList(), STAGES)
                },
                STAGES,
            )
        },
        space = { space },
        rows = { emptyList() },
        prefs = { DownloadPrefs() },
        network = { if (metered) NetworkNow(usable = true) else NetworkNow(usable = true, unmetered = true, wifi = true) },
        schedule = { scheduled.incrementAndGet() },
    )

    /** The rows of this test's show. */
    private suspend fun mine(): List<Download> = dao.all().first().filter { it.showTmdbId == SHOW_ID }

    private fun tap(button: String) {
        waitFor(hasText(button) and hasClickAction())
        compose.onNode(hasText(button) and hasClickAction()).performScrollTo().performClick()
    }

    private fun waitClosed() {
        compose.waitUntil(10_000) { closes > 0 }
        compose.waitForIdle()
        assertEquals("closed once", 1, closes)
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("never shown: ${matcher.description}", e)
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        if (!config.contains(SemanticsActions.GetTextLayoutResult)) return null
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.singleOrNull()
    }

    /** [text] shown whole: every word of it laid out, none cut at the bottom or ellipsized, and inside its window sideways. */
    private fun assertWhole(text: String) {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null }
            ?: throw AssertionError("no text '$text'")
        assertWhole(text, node)
    }

    /** A button's [text] whole, as [assertWhole]. */
    private fun assertWholeButton(text: String) {
        // In sight first: at twice the size the sheet's buttons are below the fold, and a node out of sight has no bounds.
        compose.onNode(hasText(text) and hasClickAction()).performScrollTo()
        val button = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().single()
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.textLayout() != null && button.boundsInWindow.contains(it.boundsInWindow.center) } ?: throw AssertionError("no button '$text'")
        assertWhole(text, node)
    }

    private fun assertWhole(text: String, node: SemanticsNode) {
        val layout = node.textLayout()!!
        // At the font size the case asked for: a window of its own (a sheet's, a dialog's) lays out at the system's.
        assertEquals("'$text' laid out at font scale ${layout.layoutInput.density.fontScale}", fontScale, layout.layoutInput.density.fontScale)
        val last = layout.lineCount - 1
        assertFalse(
            "'$text' is cut: ${layout.lineCount} lines in ${layout.layoutInput.constraints}",
            layout.didOverflowHeight || layout.isLineEllipsized(last) || layout.getLineEnd(last) != text.length,
        )
        val width = context.resources.displayMetrics.widthPixels
        val bounds = node.boundsInWindow
        assertTrue("'$text' ($bounds) leaves the screen (0-$width)", bounds.left >= 0f && bounds.right <= width + 1f)
    }

    private companion object {
        const val GB = 1_000_000_000L

        /** An invented show's id, which no other test's downloads have. */
        const val SHOW_ID = 990_771
        val AIRED: LocalDate = LocalDate.of(2025, 3, 4)
        val NAMES = mapOf(3 to "Fog Signal", 4 to "Low Water", 5 to "The Long Tide", 6 to "Salt and Iron", 7 to "Slack Water", 8 to "Undertow")
        val SIZES = mapOf(3 to 1_400_000_000L, 4 to 1_200_000_000L, 5 to 1_500_000_000L, 6 to 1_300_000_000L, 7 to 1_600_000_000L, 8 to 1_400_000_000L)

        /** S02E[n]'s single release by [group]. */
        fun single(n: Int, group: String = "KESTREL") = "Harbor.Lights.S02E%02d.1080p.WEB-DL.DDP5.1.H.265-%s".format(n, group)

        fun play(n: Int) = EpisodePlay(
            showTmdbId = SHOW_ID, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tvdb = 7_771, tmdb = SHOW_ID), season = 2,
            episode = n, episodeName = NAMES[n], airDate = AIRED.plusWeeks(n - 1L), runtimeMinutes = 52, seasonEpisodes = 8,
        )

        /** Season 2 as drawn: S02E01 and E02 downloaded, S02E03 to E08 listed, S02E03's download failed before. */
        val SEASON = SeasonDownloadRequest(
            "Harbor Lights", 2, (3..8).map { SeasonDownloadRequest.Listed(play(it), failedBefore = it == 3) }, downloaded = listOf(1, 2),
        )

        fun item(title: String, size: Long, score: Double) = RankedItem(
            NewznabItem(
                indexerId = "dl-season-a", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(),
                grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null,
                episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            ),
            RankedRelease(ReleaseFacts(title, title, size), ReleaseParser.parse(title), playable = true, score = score, reasons = emptyList(), estimatedBitrate = null),
        )

        /** Each episode's single release; S02E03 has a second, larger one. */
        val RELEASES: Map<Int, List<RankedItem>> = SIZES.mapValues { (n, size) ->
            listOfNotNull(item(single(n), size, 2.0), item(single(n, "ATMOS"), 3_400_000_000, 1.0).takeIf { n == 3 })
        }

        /** What the season's one search asked: two indexers by TVDB id, 38 results in all. */
        val STAGES = listOf(
            StageReport(
                SearchStage.SeasonPack,
                listOf(
                    IndexerReport(IndexerConfig("dl-season-a", "Indexer A", "https://indexer-a.invalid/api", ""), "TVDB id", found = 20),
                    IndexerReport(IndexerConfig("dl-season-b", "Indexer B", "https://indexer-b.invalid/api", ""), "TVDB id", found = 18),
                ),
            ),
        )
    }
}

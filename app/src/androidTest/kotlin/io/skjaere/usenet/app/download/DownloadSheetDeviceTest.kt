package io.skjaere.usenet.app.download

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.SearchStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Download sheet on a phone (L338, L358, L370), opened by a test host as an episode's ⋮ and a film's page will open
 * it, over the app's own runner and storage, its search a seam: invented releases in a fixed order, as a search would rank
 * them for downloading, one of them a torrent. Its host's schedule is a seam too, so nothing grabs the invented releases;
 * a host's run of a queued download is the hosts' own tests'.
 * - Finding while the search runs, nothing queued; then the pick: its quality and size, the space free, when it starts.
 * - Download queues the row, the releases that fit in their order, the torrent left out, asks for its host and closes.
 * - Choose release opens the list ranked for downloading; Back comes back to the sheet; a tap there downloads that
 *   release, first, and closes both.
 * - With the metered seam on, "Starts on Wi-Fi", on the sheet and in the list.
 * - At twice the font size, nothing cut off: Finding, the pick, and a film that won't fit.
 *
 * The free space and the queue it reads are seams, so the numbers it says are the test's. The rows it queued are deleted
 * after each case, their folders with them, and the downloads' jobs cancelled. The show and film are invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSheetDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val scheduled = AtomicInteger()
    private var closes = 0

    /** The search answers once it is let go: at once, unless a case holds it. */
    private val answer = CompletableDeferred<Unit>()

    @After
    fun tearDown() = runBlocking<Unit> {
        mine().forEach { container.downloadStorage.delete(it.id) }
        // None should be scheduled (the seam): the namespace's jobs go all the same, as every download test leaves it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    @Test
    fun findingOnlySearchesThenThePickSaysItsQualitySizeTheSpaceFreeAndWhenItStarts() = runBlocking<Unit> {
        show(EPISODE, deps())
        waitFor(hasText(DownloadSheetModel.FINDING))
        assertTrue(exists(hasText(DownloadSheetModel.FINDING_NOTE)))
        assertFalse("no Download while it finds", exists(hasText(DownloadSheetModel.TITLE) and hasClickAction()))
        assertEquals("nothing queued while it finds", emptyList<Download>(), mine())
        assertEquals(0, scheduled.get())

        answer.complete(Unit)
        waitFor(hasText(DownloadSheetModel.BEST))
        listOf(
            "Harbor Lights · S02E04 · Low Water", "1080p · WEB-DL · HEVC · about 1.2 GB", KESTREL, "14.0 GB free on this device",
            DownloadSheetModel.INDEXERS_SIZE, "Starts now", DownloadSheetModel.STAYS,
        ).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertFalse("no torrent's name", exists(hasText(TORRENT)))
        assertFalse("no line about notifications outside the app's activity", exists(hasText(DownloadSheetModel.ASKS)))
        listOf(DownloadSheetModel.TITLE, DownloadSheetModel.CHOOSE_RELEASE, "Cancel").forEach { assertTrue("its $it button", exists(hasText(it) and hasClickAction())) }
    }

    @Test
    fun downloadQueuesTheRowWithTheReleasesThatFitAsksForItsHostAndCloses() = runBlocking<Unit> {
        answer.complete(Unit)
        // 4.0 GB free: the 9.8 GB remux doesn't fit; the season pack's share of the episode does.
        show(EPISODE, deps(space = DownloadSpace(available = 5 * GB, used = 0, queued = 0)))
        tap(DownloadSheetModel.TITLE)
        waitClosed()
        val row = mine().single()
        assertEquals(DownloadState.Queued, row.state)
        assertEquals(WatchKey.episode(SHOW_ID, 2, 4), row.watchKey)
        assertEquals("Harbor Lights", row.title)
        assertEquals("S02E04 · Low Water", row.subtitle)
        assertEquals("Low Water", row.episodeName)
        assertEquals(8, row.seasonEpisodes)
        assertEquals("the pick first, then those that fit, the torrent left out", listOf(KESTREL, PACK, ATMOS), row.candidates.map { it.title })
        assertEquals(1_200_000_000, row.indexerSize)
        assertEquals("its host asked for once", 1, scheduled.get())
    }

    @Test
    fun chooseReleaseOpensTheListRankedForDownloadingAndATapThereDownloadsThatReleaseFirst() = runBlocking<Unit> {
        answer.complete(Unit)
        show(EPISODE, deps())
        tap(DownloadSheetModel.CHOOSE_RELEASE)
        waitFor(hasText("Download S02E04"))
        assertTrue(exists(hasText("Ranked for downloading: smaller first")))
        assertTrue(exists(hasText("14.0 GB free on this device. A tap downloads the release.")))
        assertFalse("the list lists Usenet's releases alone", exists(hasText(TORRENT)))

        // Back: the sheet again, as it was.
        compose.onNode(hasContentDescription("Back")).performClick()
        waitFor(hasText(DownloadSheetModel.BEST))
        assertFalse(exists(hasText("Download S02E04")))

        tap(DownloadSheetModel.CHOOSE_RELEASE)
        waitFor(hasText("Download S02E04"))
        compose.onNode(hasText(PACK) and hasClickAction()).performScrollTo().performClick()
        waitClosed()
        assertFalse("the list closed too", exists(hasText("Download S02E04")))
        assertEquals(listOf(PACK, KESTREL, ATMOS, FERRY), mine().single().candidates.map { it.title })
        assertEquals(1, scheduled.get())
    }

    @Test
    fun onMobileDataItSaysItStartsOnWifiOnTheSheetAndInTheList() = runBlocking<Unit> {
        answer.complete(Unit)
        show(EPISODE, deps(metered = true))
        waitFor(hasText("Starts on Wi-Fi"))
        assertTrue(exists(hasText("This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.")))
        assertFalse(exists(hasText("Starts now")))
        tap(DownloadSheetModel.CHOOSE_RELEASE)
        waitFor(hasText("Starts on Wi-Fi: this phone is on mobile data."))
    }

    @Test
    fun atTwiceTheFontSizeNothingIsCutOff() = runBlocking<Unit> {
        show(EPISODE, deps(metered = true), fontScale = 2f)
        waitFor(hasText(DownloadSheetModel.FINDING))
        listOf("Harbor Lights · S02E04 · Low Water", DownloadSheetModel.FINDING, DownloadSheetModel.FINDING_NOTE, "Cancel").forEach(::assertWhole)

        answer.complete(Unit)
        waitFor(hasText(DownloadSheetModel.BEST))
        listOf(
            "1080p · WEB-DL · HEVC · about 1.2 GB", KESTREL, "14.0 GB free on this device", DownloadSheetModel.INDEXERS_SIZE, "Starts on Wi-Fi",
            "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.", DownloadSheetModel.STAYS,
            DownloadSheetModel.CHOOSE_RELEASE, "Cancel",
        ).forEach(::assertWhole)
        assertWholeButton(DownloadSheetModel.TITLE)
    }

    @Test
    fun aFilmThatWontFitSaysWhatItNeedsWholeAtTwiceTheFontSize() = runBlocking<Unit> {
        answer.complete(Unit)
        // 3.0 GB free: after 1.0 GB kept free and the 1.2 GB a queued download still needs.
        show(FILM, deps(space = DownloadSpace(available = 5_200_000_000, used = 0, queued = 1_200_000_000, queuedDownloads = 1)), fontScale = 2f)
        waitFor(hasText(DownloadSheetModel.WONT_FIT))
        listOf(
            "Copper Crown · 2022", "1080p · WEB-DL · about 4.3 GB", COPPER, DownloadSheetModel.WONT_FIT, "Needs about 4.3 GB · 3.0 GB free on this device",
            "That is after the 1.2 GB the queued download still needs, and the 1.0 GB kept free for the phone.", DownloadSheetModel.WONT_FIT_NOTE,
            DownloadSheetModel.CHOOSE_RELEASE, DownloadSheetModel.STORAGE_SETTINGS, "Cancel",
        ).forEach(::assertWhole)
        assertFalse("no Download for one that won't fit", exists(hasText(DownloadSheetModel.TITLE) and hasClickAction()))

        // Its list greys it, and downloads the one that fits.
        tap(DownloadSheetModel.CHOOSE_RELEASE)
        waitFor(hasText("Download Copper Crown"))
        assertTrue(exists(hasText("✕ Won't fit: needs about 4.3 GB")))
        compose.onNode(hasText(COPPER_720) and hasClickAction()).performScrollTo().performClick()
        waitClosed()
        val row = mine().single()
        assertEquals(WatchKey.movie(FILM_ID), row.watchKey)
        assertEquals(listOf(COPPER_720), row.candidates.map { it.title })
        assertEquals(2022 to 112, row.year to row.runtimeMinutes)
    }

    /** The sheet for [request] over the app's own runner, with [deps], at [fontScale], as a page's Download opens it. */
    private fun show(request: DownloadRequest, deps: DownloadSheetDeps, fontScale: Float = 1f) {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        var open by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    if (open) {
                        DownloadSheet(request, onClose = {
                            open = false
                            closes++
                        }, deps)
                    }
                }
            }
        }
    }

    /**
     * The app's deps, but for the seams: the search ([answer] lets it go), the space free (14.0 GB unless [space] says),
     * the queue (nothing ahead), the settings (as a new install has them: Only on Wi-Fi or Ethernet on), the network (Wi-Fi,
     * or mobile data where [metered]) and the host's schedule, counted.
     */
    private fun deps(metered: Boolean = false, space: DownloadSpace = DownloadSpace(available = 15 * GB, used = 0, queued = 0)) =
        DownloadSheetDeps.of(container, ask = null).copy(
            findEpisode = { request, _ ->
                flow {
                    answer.await()
                    emit(FinderEvent.Finished(FindResult(request, EPISODE_RELEASES.map { FoundRelease(it.item, it.ranked, SearchStage.Episode) }, emptyList(), emptyList())))
                }
            },
            findMovie = { _, context ->
                flowOf(
                    MovieFind(
                        ResultGroup("copper crown|2022", GroupKind.Movie, null, emptyList(), null, 2022, FILM_RELEASES, emptyList()),
                        listOf(IndexerStatus("dl-sheet-a", "Indexer A", IndexerStatus.State.Answered(FILM_RELEASES.size))), context,
                    ),
                )
            },
            space = { space },
            rows = { emptyList() },
            prefs = { DownloadPrefs() },
            network = { if (metered) NetworkNow(usable = true) else NetworkNow(usable = true, unmetered = true, wifi = true) },
            schedule = { scheduled.incrementAndGet() },
        )

    /** The rows of this test's episode and film. */
    private suspend fun mine(): List<Download> = dao.all().first().filter { it.watchKey == WatchKey.episode(SHOW_ID, 2, 4) || it.watchKey == WatchKey.movie(FILM_ID) }

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

    /** A button's [text] whole, as [assertWhole], where the same words are also a title's. */
    private fun assertWholeButton(text: String) {
        val button = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().single()
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.textLayout() != null && button.boundsInWindow.contains(it.boundsInWindow.center) } ?: throw AssertionError("no button '$text'")
        assertWhole(text, node)
    }

    private fun assertWhole(text: String, node: SemanticsNode) {
        val layout = node.textLayout()!!
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
        const val SHOW_ID = 990_417
        const val FILM_ID = 990_418
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val TORRENT = "Harbor.Lights.S02E04.1080p.WEB.H264-SALTMARSH"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"
        const val FERRY = "Harbor.Lights.S02E04.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val COPPER = "Copper.Crown.2022.1080p.WEB-DL.DDP5.1.H.264-KESTREL"
        const val COPPER_BLURAY = "Copper.Crown.2022.1080p.BluRay.x264-DRIFTWOOD"
        const val COPPER_720 = "Copper.Crown.2022.720p.WEB-DL.DDP5.1.H.264-KESTREL"

        val EPISODE = DownloadRequest.Episode(
            EpisodePlay(
                showTmdbId = SHOW_ID, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = SHOW_ID), season = 2,
                episode = 4, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
            ),
        )
        val FILM = DownloadRequest.Movie(MovieTarget(FILM_ID, "Copper Crown", 2022, imdb = null, runtimeMinutes = 112))

        fun item(title: String, size: Long, indexer: String = "dl-sheet-a", hash: String? = null, score: Double) = RankedItem(
            NewznabItem(
                indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(),
                grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null,
                episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(), infoHash = hash,
            ),
            RankedRelease(
                ReleaseFacts(title, title, size, source = if (hash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET), ReleaseParser.parse(title),
                playable = true, score = score, reasons = emptyList(), estimatedBitrate = null,
            ),
        )

        /** S02E04's, in their order for downloading: an 11.2 GB pack of the season's 8 episodes comes to 1.4 GB here. */
        val EPISODE_RELEASES = listOf(
            item(KESTREL, 1_200_000_000, score = 5.0),
            item(TORRENT, 1_800_000_000, indexer = "dl-sheet-t", hash = "0123456789abcdef0123456789abcdef01234567", score = 4.0),
            item(PACK, 11_200_000_000, indexer = "dl-sheet-b", score = 3.0),
            item(ATMOS, 3_400_000_000, score = 2.0),
            item(FERRY, 9_800_000_000, indexer = "dl-sheet-b", score = 1.0),
        )

        val FILM_RELEASES = listOf(item(COPPER, 4_300_000_000, score = 3.0), item(COPPER_BLURAY, 8_900_000_000, score = 2.0), item(COPPER_720, 2_200_000_000, score = 1.0))
    }
}

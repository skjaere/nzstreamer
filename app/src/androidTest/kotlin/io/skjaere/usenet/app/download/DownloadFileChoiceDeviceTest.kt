package io.skjaere.usenet.app.download

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FetchedNzb
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseKey
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.FindResult
import io.skjaere.usenet.search.FinderEvent
import io.skjaere.usenet.search.FoundRelease
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.SearchStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Choose file in download mode on a phone (L403, L435), from the Download sheet's Choose release, over the app's own runner
 * and storage. The search, the pack's grab and its prepare are seams (no indexer or provider is asked), and so are the
 * space free (1.5 GB), the network and the host's schedule. An invented season pack of eight episodes and a sample, posted a
 * file a volume, and two singles.
 * - The pack's ⋮ › Choose file: Fetching NZB and Opening in the play sheet's words while it waits, the release under them,
 *   what it does, Cancel; then its videos, largest first with their exact sizes, the episode's named, the finale greyed as
 *   it won't fit; one grab and one prepare, nothing in the NZB store. A pick goes back to the sheet as its Chosen file.
 * - Download queues it: the pack first, then the single that fits; its pick, the pack's NZB and the indexer's password
 *   kept beside its marker; the file's own size as its need; the host asked for once.
 * - Cancel while it opens goes back to the list.
 * - A pack that can't be opened says why, with Try another release (back to the list, the pack marked) and Close (back to
 *   the sheet).
 * - At twice the font size the wait, the files, the Chosen file and the failure are whole, their content laid out at that
 *   size in the test's window (the sheet's own window takes the system's).
 *
 * The rows it queued are deleted after each case, their folders with them, and the downloads' jobs cancelled.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFileChoiceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val dao = container.data.downloads
    private val scheduled = AtomicInteger()
    private val grabs = AtomicInteger()
    private val prepares = AtomicInteger()
    private var closes = 0
    private var fontScale = 1f

    /** The pack's grab and its prepare answer once they are let go: at once, unless a case holds them. */
    private var grab = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var opened = CompletableDeferred<Unit>().apply { complete(Unit) }

    /** Whether the grab under way was stopped before it answered. */
    @Volatile
    private var grabStopped = false

    /** What the pack's prepare answers: its files, unless a case says otherwise. */
    private var answer: suspend (ByteArray, String, String?) -> PrepareOutcome = { nzb, title, password -> PREPARER.prepare(nzb, title, password) }

    @After
    fun tearDown() = runBlocking<Unit> {
        mine().forEach { container.downloadStorage.delete(it.id) }
        // None should be scheduled (the seam): the namespace's jobs go all the same, as every download test leaves it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
    }

    @Test
    fun chooseFileWaitsForThePackThenListsItsVideosAndAPickComesBackToTheSheetAsTheChosenFile() = runBlocking<Unit> {
        grab = CompletableDeferred()
        opened = CompletableDeferred()
        show()
        openChooseFile()
        waitFor(hasText("Fetching NZB"))
        listOf("Opening", DownloadFileChoice.OPENING_NOTE, "Season pack", "Harbor Lights · S02E04 · Low Water").forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertTrue("the release under the steps", compose.onAllNodes(hasText(PACK)).fetchSemanticsNodes().size >= 2)
        assertTrue(exists(hasText("Cancel") and hasClickAction()))
        assertEquals(1, grabs.get())
        assertEquals(0, prepares.get())

        grab.complete(Unit)
        compose.waitUntil(5_000) { prepares.get() == 1 }
        opened.complete(Unit)
        waitFor(hasText("Looking for S02E04 'Low Water'"))
        listOf(
            "Choose the file to download as S02E04:", "1.5 GB free on this device. Files that won't fit are greyed.", "Harbor.Lights.S02E08.mkv",
            "✕ Won't fit: needs 1.6 GB", "Named S02E04 · 1.3 GB",
        ).forEach { assertTrue("'$it' shown", exists(hasText(it, substring = true))) }
        // The finale is greyed: nothing a tap chooses.
        assertFalse(exists(hasText("Harbor.Lights.S02E08.mkv") and hasClickAction()))
        assertEquals("one grab", 1, grabs.get())
        assertEquals("one prepare", 1, prepares.get())
        // Outside the NZB store (DN1's L383): nothing of the pack is kept there while its files are listed.
        assertNull(container.store.get(PreparedNzb.idFor(PACK_NZB)))
        assertNull(container.store.stored(PreparedNzb.idFor(PACK_NZB)))

        compose.onNode(hasText("Harbor.Lights.S02E04.mkv") and hasClickAction()).performScrollTo().performClick()
        waitFor(hasText(DownloadSheetModel.CHOSEN))
        assertFalse("the list closed", exists(hasText("Download S02E04")))
        listOf(
            "1080p · WEB-DL · 1.3 GB", PACK, "Season pack · only this file is downloaded:", "Harbor.Lights.S02E04.mkv", "1.5 GB free on this device",
            DownloadSheetModel.FILES_SIZE, "Starts now", DownloadSheetModel.STAYS,
        ).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertFalse("no Best match in its place", exists(hasText(DownloadSheetModel.BEST)))
        listOf(DownloadSheetModel.TITLE, DownloadSheetModel.CHOOSE_RELEASE, "Cancel").forEach { assertTrue("its $it button", exists(hasText(it) and hasClickAction())) }
        assertEquals("nothing queued before Download", emptyList<Download>(), mine())
        assertEquals(0, scheduled.get())
    }

    @Test
    fun downloadQueuesTheChosenFileWithItsPackFirstItsNzbBesideItsRowAndItsSizeAsItsNeed() = runBlocking<Unit> {
        show()
        openChooseFile()
        waitFor(hasText("Harbor.Lights.S02E04.mkv") and hasClickAction())
        compose.onNode(hasText("Harbor.Lights.S02E04.mkv") and hasClickAction()).performScrollTo().performClick()
        waitFor(hasText(DownloadSheetModel.CHOSEN))
        tap(DownloadSheetModel.TITLE)
        compose.waitUntil(10_000) { closes > 0 }
        compose.waitForIdle()
        val row = mine().single()
        assertEquals(DownloadState.Queued, row.state)
        assertEquals("the pack first, then the single that fits", listOf(PACK, KESTREL), row.candidates.map { it.title })
        assertEquals("the file's own size, not the pack's share", E04_SIZE, row.indexerSize)
        val index = VIDEOS.indexOfFirst { it.first == "Harbor.Lights.S02E04.mkv" }
        assertEquals(DownloadPick(index, "Harbor.Lights.S02E04.mkv", E04_SIZE, PreparedNzb.idFor(PACK_NZB)), DownloadColumns.pick(row))
        // The pack's NZB and its indexer's password beside its marker: its attempt grabs nothing.
        val kept = checkNotNull(container.downloadStorage.grab(row.id)) { "no NZB kept beside the row" }
        assertEquals(ReleaseKey.of(INDEXER_B, PACK), kept.key)
        assertArrayEquals(PACK_NZB, kept.source.nzb)
        assertEquals(PASSWORD, kept.source.password)
        assertEquals(1, grabs.get())
        assertEquals(1, prepares.get())
        assertEquals("its host asked for once", 1, scheduled.get())
    }

    @Test
    fun cancelWhileThePackOpensGoesBackToTheList() = runBlocking<Unit> {
        grab = CompletableDeferred()
        show()
        openChooseFile()
        waitFor(hasText("Fetching NZB"))
        tap("Cancel")
        compose.waitUntil(5_000) { !exists(hasText("Fetching NZB")) }
        assertTrue("the list", exists(hasText("Download S02E04")))
        compose.waitUntil(5_000) { grabStopped }
        assertEquals(0, prepares.get())
        assertEquals(emptyList<Download>(), mine())
    }

    @Test
    fun aPackThatCantBeOpenedSaysWhyTryAnotherReleaseMarksItInTheListAndCloseGoesBackToTheSheet() = runBlocking<Unit> {
        answer = { _, _, _ -> PrepareOutcome.MissingArticles("an article gone") }
        show()
        openChooseFile()
        waitFor(hasText(DownloadFileChoice.FAILED))
        listOf("✕ Incomplete on your providers", DownloadFileChoice.FAILED_NOTE).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        listOf(DownloadFileChoice.TRY_ANOTHER, "Close").forEach { assertTrue("its $it button", exists(hasText(it) and hasClickAction())) }

        tap(DownloadFileChoice.TRY_ANOTHER)
        compose.waitUntil(5_000) { !exists(hasText(DownloadFileChoice.FAILED)) }
        // Back in the list, where the pack says why it failed.
        waitFor(hasText(PACK) and hasText("✕ Incomplete on your providers"))
        assertTrue(exists(hasText("Download S02E04")))

        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        waitFor(hasText("Choose file") and hasClickAction())
        compose.onNode(hasText("Choose file") and hasClickAction()).performClick()
        waitFor(hasText(DownloadFileChoice.FAILED))
        tap("Close")
        waitFor(hasText(DownloadSheetModel.BEST))
        assertFalse("the list closed too", exists(hasText("Download S02E04")))
        assertFalse(exists(hasText(DownloadFileChoice.FAILED)))
        assertEquals(emptyList<Download>(), mine())
    }

    @Test
    fun atTwiceTheFontSizeTheWaitTheFilesAndTheChosenFileAreWhole() = runBlocking<Unit> {
        grab = CompletableDeferred()
        opened = CompletableDeferred()
        val model = showContent()
        compose.runOnIdle { model.chooseFile(pack()) }
        waitFor(hasText("Fetching NZB"))
        listOf("Harbor Lights · S02E04 · Low Water", "Fetching NZB", "Opening", "Season pack", PACK, DownloadFileChoice.OPENING_NOTE, "Cancel").forEach(::assertWhole)

        grab.complete(Unit)
        opened.complete(Unit)
        waitFor(hasText("Looking for S02E04 'Low Water'"))
        listOf(
            "Looking for S02E04 'Low Water'", "Choose the file to download as S02E04:", "1.5 GB free on this device. Files that won't fit are greyed.",
            "Harbor.Lights.S02E08.mkv", "✕ Won't fit: needs 1.6 GB", "Harbor.Lights.S02E04.mkv", "Named S02E04 · 1.3 GB", "Harbor.Lights.S02.Sample.mkv", "Sample · 50 MB",
        ).forEach(::assertWhole)

        val choice = checkNotNull(model.choice.value) { "no Choose file open" }
        compose.runOnIdle { choice.pick(VIDEOS.indexOfFirst { it.first == "Harbor.Lights.S02E04.mkv" }) }
        waitFor(hasText(DownloadSheetModel.CHOSEN))
        listOf(
            DownloadSheetModel.CHOSEN, "1080p · WEB-DL · 1.3 GB", PACK, "Season pack · only this file is downloaded:", "Harbor.Lights.S02E04.mkv",
            "1.5 GB free on this device", DownloadSheetModel.FILES_SIZE, "Starts now", DownloadSheetModel.STAYS, DownloadSheetModel.CHOOSE_RELEASE, "Cancel",
        ).forEach(::assertWhole)
    }

    @Test
    fun atTwiceTheFontSizeAPackThatFailedIsWhole() = runBlocking<Unit> {
        answer = { _, _, _ -> PrepareOutcome.MissingArticles("an article gone") }
        val model = showContent()
        compose.runOnIdle { model.chooseFile(pack()) }
        waitFor(hasText(DownloadFileChoice.FAILED))
        listOf(DownloadFileChoice.FAILED, "✕ Incomplete on your providers", PACK, DownloadFileChoice.FAILED_NOTE, DownloadFileChoice.TRY_ANOTHER, "Close")
            .forEach(::assertWhole)
    }

    /** The sheet for S02E04 over the app's own runner and these seams, as an episode's ⋮ opens it. */
    private fun show() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            AppTheme {
                if (open) {
                    DownloadSheet(EPISODE, onClose = {
                        open = false
                        closes++
                    }, deps())
                }
            }
        }
        waitFor(hasText(DownloadSheetModel.BEST))
    }

    /**
     * The sheet's model's content at twice the font size in the test's own window, Choose file's over it where one is open:
     * the sheets' windows lay their words out at the system's font size, whatever a page's density says.
     */
    private fun showContent(): DownloadSheetModel {
        fontScale = 2f
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        lateinit var shown: DownloadSheetModel
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    val scope = rememberCoroutineScope()
                    val model = remember { DownloadSheetModel(EPISODE, deps(), scope).also { shown = it } }
                    LaunchedEffect(model) { model.start() }
                    val choice by model.choice.collectAsState()
                    choice?.let { DownloadFileChoiceContent(it, it::pick, onCancel = {}, onTryAnother = {}, onClose = {}) }
                        ?: DownloadSheetContent(EPISODE, model, onChoose = {}, onClose = {})
                }
            }
        }
        waitFor(hasText(DownloadSheetModel.BEST))
        return shown
    }

    /** Choose release, then the pack's ⋮ › Choose file. */
    private fun openChooseFile() {
        tap(DownloadSheetModel.CHOOSE_RELEASE)
        waitFor(hasText("Download S02E04"))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("More for $PACK"))
        compose.onNode(hasContentDescription("More for $PACK")).performClick()
        waitFor(hasText("Choose file") and hasClickAction())
        compose.onNode(hasText("Choose file") and hasClickAction()).performClick()
    }

    /** The pack as the search found it, from the list the sheet shows. */
    private fun pack(): RankedItem = RELEASES.single { it.item.title == PACK }

    /**
     * The app's deps, but for the seams: the search, the space free (1.5 GB), the queue (nothing ahead), the settings (as a
     * new install has them), Wi-Fi, the host's schedule, counted, and the pack's grab and prepare, counted and held where a
     * case holds them.
     */
    private fun deps() = DownloadSheetDeps.of(container, ask = null, context).copy(
        findEpisode = { request, _ ->
            flowOf(FinderEvent.Finished(FindResult(request, RELEASES.map { FoundRelease(it.item, it.ranked, SearchStage.Episode) }, emptyList(), emptyList())))
        },
        space = { DownloadSpace(available = 1_500_000_000L + DownloadSpace.KEPT_FREE, used = 0, queued = 0) },
        rows = { emptyList() },
        prefs = { DownloadPrefs() },
        network = { NetworkNow(usable = true, unmetered = true, wifi = true) },
        schedule = { scheduled.incrementAndGet() },
        grab = { item ->
            assertEquals(PACK, item.item.title)
            grabs.incrementAndGet()
            try {
                grab.await()
            } finally {
                if (!grab.isCompleted) grabStopped = true
            }
            FetchedNzb(PACK_NZB, PASSWORD)
        },
        prepare = { nzb, title, password ->
            prepares.incrementAndGet()
            opened.await()
            answer(nzb, title, password)
        },
    )

    /** The rows of this test's episode. */
    private suspend fun mine(): List<Download> = dao.all().first().filter { it.watchKey == WatchKey.episode(SHOW_ID, 2, 4) }

    private fun tap(button: String) {
        waitFor(hasText(button) and hasClickAction())
        compose.onNode(hasText(button) and hasClickAction()).performScrollTo().performClick()
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

    /** [text] shown whole, at the case's font size: every word laid out, none cut at the bottom or ellipsized, inside the screen sideways. */
    private fun assertWhole(text: String) {
        val node = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.textLayout() != null }
            ?: throw AssertionError("no text '$text'")
        val layout = node.textLayout()!!
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
        const val SHOW_ID = 990_941
        const val INDEXER_A = "dl-fc-a"
        const val INDEXER_B = "dl-fc-b"
        const val PASSWORD = "pw-dl-fc"
        const val KESTREL = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.265-KESTREL"
        const val PACK = "Harbor.Lights.S02.1080p.WEB-DL.DDP5.1.H.264-DRIFTWOOD"
        const val ATMOS = "Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.Atmos.H.264-TIDEPOOL"
        const val E04_SIZE = 1_310_000_000L

        val EPISODE = DownloadRequest.Episode(
            EpisodePlay(
                showTmdbId = SHOW_ID, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = SHOW_ID), season = 2,
                episode = 4, episodeName = "Low Water", airDate = LocalDate.of(2025, 3, 25), runtimeMinutes = 52, seasonEpisodes = 8,
            ),
        )

        fun item(title: String, size: Long, indexer: String, score: Double) = RankedItem(
            NewznabItem(
                indexerId = indexer, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null, categories = emptyList(),
                grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null,
                episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
            ),
            RankedRelease(ReleaseFacts(title, title, size), ReleaseParser.parse(title), playable = true, score = score, reasons = emptyList(), estimatedBitrate = null),
        )

        /** S02E04's, in their order for downloading: the 11.2 GB pack of the season's 8 episodes comes to 1.4 GB here, and fits. */
        val RELEASES = listOf(item(KESTREL, 1_200_000_000, INDEXER_A, 3.0), item(PACK, 11_200_000_000, INDEXER_B, 2.0), item(ATMOS, 3_400_000_000, INDEXER_A, 1.0))

        /** The pack's videos as nzb-streamer lists them, in the NZB's order, and a sample: the finale too big for 1.5 GB. */
        val VIDEOS = listOf(
            "Harbor.Lights.S02E01.mkv" to 1_410_000_000L, "Harbor.Lights.S02E02.mkv" to 1_300_000_000L, "Harbor.Lights.S02E03.mkv" to 1_400_000_000L,
            "Harbor.Lights.S02E04.mkv" to E04_SIZE, "Harbor.Lights.S02E05.mkv" to 1_390_000_000L, "Harbor.Lights.S02E06.mkv" to 1_420_000_000L,
            "Harbor.Lights.S02E07.mkv" to 1_290_000_000L, "Harbor.Lights.S02E08.mkv" to 1_600_000_000L, "Harbor.Lights.S02.Sample.mkv" to 50_000_000L,
        )

        /** The pack's NZB: a volume of its own for each file, as it is posted, and its NFO. */
        val PACK_NZB: ByteArray = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
            (VIDEOS + ("Harbor.Lights.S02.nfo" to 4_000L)).forEach { (name, size) ->
                append("<file poster=\"fake\" date=\"0\" subject=\"&quot;$name&quot; yEnc (1/1)\"><groups><group>alt.binaries.fake</group></groups>")
                append("<segments><segment bytes=\"$size\" number=\"1\">dl-fc-pack/$name@fake</segment></segments></file>\n")
            }
            append("</nzb>\n")
        }.encodeToByteArray()

        /** nzb-streamer's prepare as a release posted so lists it: each video a volume and a file to stream, at its size. */
        val PREPARER = NzbPreparer(
            object : PrepareBackend {
                override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult {
                    val sizes = VIDEOS.toMap()
                    val volumes = NzbParser.parse(nzbBytes).files.mapNotNull { file ->
                        val name = checkNotNull(FilenameResolver.extractSubjectFilename(file.subject))
                        sizes[name]?.let { size -> file.also { it.yencHeaders = YencHeaders(line = 128, size = size, name = name) } }
                    }
                    val response = NzbMetadataResponse(volumes = volumes.map { it.yencHeaders!!.name }, obfuscated = false, entries = emptyList())
                    return PrepareResult.Success(ExtractedMetadata.Raw(response = response, orderedArchiveNzb = NzbDocument(volumes, password)))
                }

                override suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile> =
                    metadata.orderedArchiveNzb.files.mapIndexed { i, volume -> StreamableFile(volume.yencHeaders!!.name, volume.yencHeaders!!.size, i, 0, 0, 0) }
            },
            Dispatchers.Default,
        )
    }
}

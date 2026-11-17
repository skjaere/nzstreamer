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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Import's Download sheet for a file of an NZB it opened (L455), composed over a [DownloadRequest.File] as
 * DownloadSheetDeviceTest composes its sheet over a page's request (Import's own button comes with its rows): an invented
 * Quiet Meridian season 1 as one NZB of two episodes, each a split RAR4 set, posted on the test's own NNTP server
 * (LocalNntp, DownloadFixtures) under ids no run used, and prepared on the engine as Import prepares it, with a password
 * typed for it. The request is the one Import's Download makes from what it holds ([DownloadRequest.File.of]).
 * - "This file": the file's quality with its exact size (no "about"), the NZB's name, that only this file is downloaded;
 *   the space free with where its size comes from, when it starts, where downloads stay; Download and Cancel, no Choose
 *   release. Download queues its row through the app's runner, the NZB kept in `.packs/` beside it, asks for its host once
 *   (a seam), and closes.
 * - Won't fit: what it needs, exactly, why less is free, the storage note alone; Storage settings and Cancel, with no
 *   Download and no Choose release. At twice the font size, whole.
 * - At twice the font size, the sheet's every line whole.
 *
 * No finder is asked: the searches fail the case if they are. The free space, the queue, the settings and the network
 * are seams, so the numbers the sheet says are the test's. The rows queued are deleted after each case with their
 * folders, the NZB's copy with them, and the downloads' jobs cancelled; the server ends with its articles.
 */
@RunWith(AndroidJUnit4::class)
class ImportDownloadDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val scheduled = AtomicInteger()
    private var closes = 0

    /** The font size the case shows the sheet at, which its words are laid out at. */
    private var fontScale = 1f

    /** The NZB Import opened: its bytes, and the copy's key once a case has queued one of its files. */
    private lateinit var nzb: ByteArray
    private var copyKey: String? = null

    /** The second episode's file, as Import's Download asks for it. */
    private lateinit var request: DownloadRequest.File

    @Before
    fun setUp() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(fixtures.local.provider)))
        }
        val salt = System.nanoTime()
        val episodes = (1..2).map { e ->
            fixtures.post(Packing.Rar4, "Quiet.Meridian.S01E%02d.1080p.WEB-DL.H264".format(e), DownloadFixtures.seeded(16 * DownloadFixtures.MB, salt + e), salt = "$salt")
        }
        nzb = fixtures.local.nzb(*episodes.flatMap { it.posted }.toTypedArray())
        val outcome = engineSetup("the NZB prepared as Import prepares it") { container.preparer.prepare(nzb, NZB_TITLE, PASSWORD) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("the NZB didn't prepare: $outcome")
        val file = prepared.files.firstOrNull { it.name == E02 } ?: throw AssertionError("no $E02 in ${prepared.files.size} files")
        request = DownloadRequest.File.of(prepared, file, nzb) ?: throw AssertionError("no Download for $E02")
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        mine().forEach { container.downloadStorage.delete(it.id) }
        copyKey?.let { container.downloadStorage.packs.folder(it).deleteRecursively() }
        // None should be scheduled (the seam): the namespace's jobs go all the same, as every download test leaves it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
        fixtures.close()
    }

    @Test
    fun theSheetSaysThisFileWithItsExactSizeAndDownloadQueuesItWithItsNzb() = runBlocking<Unit> {
        show(deps())
        waitFor(hasText(DownloadSheetModel.THIS_FILE))
        val size = formatSize(request.size)
        listOf(
            "Quiet Meridian · S01E02", "1080p · WEB-DL · $size", NZB_TITLE, "Season pack · only this file is downloaded:", E02,
            "14.0 GB free on this device", DownloadSheetModel.FILES_SIZE, "Starts now", DownloadSheetModel.STAYS,
        ).forEach { assertTrue("'$it' shown", exists(hasText(it))) }
        assertFalse("no 'about': the size is the file's own", exists(hasText("about", substring = true)))
        assertFalse("no line about notifications outside the app's activity", exists(hasText(DownloadSheetModel.ASKS)))
        assertFalse("nothing to choose from", exists(hasText(DownloadSheetModel.CHOOSE_RELEASE)))
        listOf(DownloadSheetModel.TITLE, "Cancel").forEach { assertTrue("its $it button", exists(hasText(it) and hasClickAction())) }

        tap(DownloadSheetModel.TITLE)
        waitClosed()
        val row = mine().single()
        assertEquals(DownloadKind.Release, row.kind)
        assertEquals(DownloadState.Queued, row.state)
        assertEquals(E02, row.title)
        assertEquals(NZB_TITLE, row.subtitle)
        assertEquals(listOf(DownloadCandidate(IMPORTED, request.nzbId, NZB_TITLE, size = request.size)), row.candidates)
        assertEquals(DownloadPick(request.file.index, E02, request.size, request.nzbId), DownloadColumns.pick(row))
        assertEquals("the file's exact size", request.size, row.indexerSize)
        // The NZB kept for its downloads in `.packs/`, under Import's release of it (L423).
        val key = ReleaseKey.of(IMPORTED, request.nzbId).also { copyKey = it }
        val copy = container.downloadStorage.packs.find(key) ?: throw AssertionError("no copy of the NZB kept")
        assertArrayEquals(nzb, copy.source.nzb)
        assertEquals("its host asked for once", 1, scheduled.get())
    }

    @Test
    fun aFileThatWontFitSaysWhatItNeedsExactlyWithStorageSettingsAndNoDownloadWholeAtTwiceTheFontSize() = runBlocking<Unit> {
        // 10 MB free, after the 1.0 GB kept free: the file doesn't fit.
        showContent(deps(space = DownloadSpace(available = DownloadSpace.KEPT_FREE + 10_000_000, used = 0, queued = 0)), fontScale = 2f)
        waitFor(hasText(DownloadSheetModel.WONT_FIT))
        listOf(
            "Quiet Meridian · S01E02", DownloadSheetModel.THIS_FILE, "1080p · WEB-DL · ${formatSize(request.size)}", NZB_TITLE, E02, DownloadSheetModel.WONT_FIT,
            "Needs ${formatSize(request.size)} · 10 MB free on this device", "That is after the 1.0 GB kept free for the phone.", DownloadSheetModel.STORAGE_NOTE,
            DownloadSheetModel.STORAGE_SETTINGS, "Cancel",
        ).forEach(::assertWhole)
        assertTrue("its Storage settings button", exists(hasText(DownloadSheetModel.STORAGE_SETTINGS) and hasClickAction()))
        assertFalse("no Download for a file that won't fit", exists(hasText(DownloadSheetModel.TITLE) and hasClickAction()))
        assertFalse("nothing to choose from", exists(hasText(DownloadSheetModel.CHOOSE_RELEASE)))
        assertFalse(exists(hasText(DownloadSheetModel.WONT_FIT_NOTE)))
    }

    @Test
    fun atTwiceTheFontSizeTheSheetIsWhole() = runBlocking<Unit> {
        showContent(deps(metered = true), fontScale = 2f)
        waitFor(hasText(DownloadSheetModel.THIS_FILE))
        listOf(
            "Quiet Meridian · S01E02", DownloadSheetModel.THIS_FILE, "1080p · WEB-DL · ${formatSize(request.size)}", NZB_TITLE,
            "Season pack · only this file is downloaded:", E02, "14.0 GB free on this device", DownloadSheetModel.FILES_SIZE, "Starts on Wi-Fi",
            "This phone is on mobile data. Downloads wait for Wi-Fi or Ethernet: Settings › Downloads.", DownloadSheetModel.STAYS, "Cancel",
        ).forEach(::assertWhole)
        assertWholeButton(DownloadSheetModel.TITLE)
    }

    /**
     * The sheet's content for [request] over [deps], at [fontScale], in the test's own window: the sheet's window lays its
     * words out at the system's font size, whatever a page's density says, so a case at twice the size shows its content.
     */
    private fun showContent(deps: DownloadSheetDeps, fontScale: Float) {
        this.fontScale = fontScale
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scaled)) {
                AppTheme {
                    val scope = rememberCoroutineScope()
                    val model = remember { DownloadSheetModel(request, deps, scope) }
                    LaunchedEffect(model) { model.start() }
                    DownloadSheetContent(request, model, onChoose = {}, onClose = {})
                }
            }
        }
    }

    /** The sheet for [request] over the app's own runner, with [deps], as Import's Download opens it. */
    private fun show(deps: DownloadSheetDeps) {
        var open by mutableStateOf(true)
        compose.setContent {
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

    /**
     * The app's deps, the runner's import among them, but for the seams: no search (each fails the case if asked), the
     * space free (14.0 GB unless [space] says), the queue (nothing ahead), the settings (as a new install has them: Only on
     * Wi-Fi or Ethernet on), the network (Wi-Fi, or mobile data where [metered]) and the host's schedule, counted.
     */
    private fun deps(metered: Boolean = false, space: DownloadSpace = DownloadSpace(available = 15 * GB, used = 0, queued = 0)) =
        DownloadSheetDeps.of(container, ask = null, context).copy(
            findEpisode = { _, _ -> throw AssertionError("an episode's search asked") },
            findMovie = { _, _ -> throw AssertionError("a film's search asked") },
            findSeason = { _, _ -> throw AssertionError("a season's search asked") },
            space = { space },
            rows = { emptyList() },
            prefs = { DownloadPrefs() },
            network = { if (metered) NetworkNow(usable = true) else NetworkNow(usable = true, unmetered = true, wifi = true) },
            schedule = { scheduled.incrementAndGet() },
        )

    /** The rows of this case's file. */
    private suspend fun mine(): List<Download> = container.data.downloads.all().first().filter { it.watchKey == WatchKey.release(request.nzbId, request.file.index) }

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
        const val NZB_TITLE = "Quiet.Meridian.S01.1080p.WEB-DL.H264"
        const val E02 = "Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv"

        /** The archive password typed on Import for the NZB. */
        const val PASSWORD = "typed-on-import"
    }
}

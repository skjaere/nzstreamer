package io.skjaere.usenet.app.download

import android.app.Application
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.compressionutils.generation.ArchiveFile
import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.usenet.app.ImportScreen
import io.skjaere.usenet.app.ImportViewModel
import io.skjaere.usenet.app.NetworkNow
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.IMPORTED
import io.skjaere.usenet.app.play.IMPORT_GONE
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Import's downloads (L454, L455, L458, L469), over the test's own NNTP server (LocalNntp, DownloadFixtures)
 * with the engine on it, the NZBs' files posted under ids no run used: an invented Quiet Meridian season 1, its episodes and
 * its .nfo in one stored RAR5 set as packs post them (the one downloaded end to end the device tests' chapters.mkv, which
 * Resolve's probe reads).
 *
 * The Download sheet, composed over a [DownloadRequest.File] as DownloadSheetDeviceTest composes its sheet over a page's
 * request, for a season pack of two split RAR4 sets prepared with the archive password typed for it:
 * - "This file": the file's quality with its exact size (no "about"), the NZB's name, that only this file is downloaded;
 *   the space free with where its size comes from, when it starts, where downloads stay; Download and Cancel, no Choose
 *   release. Download queues its row through the app's runner, the NZB kept in `.packs/` beside it, asks for its host once
 *   (a seam), and closes.
 * - Won't fit: what it needs, exactly, why less is free, the storage note alone; Storage settings and Cancel, with no
 *   Download and no Choose release. At twice the font size, whole.
 * - At twice the font size, the sheet's every line whole.
 *
 * Import itself, with the NZB handed over as another app hands one ([io.skjaere.usenet.app.NzbHandoff]), as ImportDeviceTest
 * opens one, its sheet the app's over the seams below:
 * - Each video's row ends in a 48 dp Download button read "Download <name>", the arrow; the .nfo has none.
 * - Download from the row: the sheet; then the row queued, Stored data cleared (the NZB store emptied) meanwhile, its
 *   download through the app's runner from the copy alone, the button the ring as its file is written (throttled), then
 *   the tick and "Downloaded"; the file whole, the copy gone. A tap on the row then plays the file: the launch of the
 *   download, its watch the release's, with no NZB streamed.
 * - The button's menu by state (queued, written, finished, failed), Open Downloads opening the page; Download again on a
 *   failed one whose copy is gone queues it again, the same row, with its copy written again; Remove's Undo bar.
 * - A copy of a disc: none of its files has a button, not even its sample.
 * - At twice the font size the names wrap whole and each button keeps 48 dp at the row's end.
 *
 * No finder is asked: the searches fail the case if they are. The free space, the queue, the settings, the network and
 * the host's schedule are seams; the walk that writes the file is the app's runner's, run by the case. Put back after
 * each: the rows and their folders, the NZBs' copies, the throttle, the walk, the downloads' jobs and the notifications of
 * the rows; the server ends with its articles.
 */
@RunWith(AndroidJUnit4::class)
class ImportDownloadDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val fixtures = DownloadFixtures()
    private val seeds = DownloadsPageSeeds(context, container)
    private val scheduled = CopyOnWriteArrayList<ScheduleCause>()
    private var closes = 0
    private var downloadsOpened = 0

    /** The font size the case shows the sheet at, which its words are laid out at. */
    private var fontScale = 1f

    /** The NZBs the case opened, by id: their rows and copies are the case's own. */
    private val nzbIds = CopyOnWriteArrayList<String>()

    /** The season pack the sheet's cases download from: its bytes, and the second episode's file as Import's Download asks for it. */
    private lateinit var nzb: ByteArray
    private lateinit var request: DownloadRequest.File

    /** Import's model where a case opened Import, and the walk where a case ran one. */
    private var vm: ImportViewModel? = null
    private var walk: CoroutineScope? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val throttle = File(context.filesDir, "${DownloadStorage.DIR}/${DownloadDebug.THROTTLE}")

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads never show on a TV (W108): tv.TvNoDownloadsTest", container.television)
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(fixtures.local.provider)))
        }
        seeds.before()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // The walk over before its rows go: cancelled, it can still be ending a row, which nothing stops.
        walk?.coroutineContext?.job?.cancelAndJoin()
        throttle.delete()
        monitor?.let(instrumentation::removeMonitor)
        vm?.viewModelScope?.cancel()
        onMainThread { container.playback.cancel() }
        val rows = mine()
        rows.forEach { container.downloadStorage.delete(it.id) }
        seeds.after()
        nzbIds.forEach { container.downloadStorage.packs.folder(ReleaseKey.of(IMPORTED, it)).deleteRecursively() }
        val manager = context.getSystemService(NotificationManager::class.java)
        rows.forEach { manager.cancel(it.id, DownloadNotifications.DONE_ID) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadJobService.scheduler(context).cancelAll()
        fixtures.close()
    }

    @Test
    fun theSheetSaysThisFileWithItsExactSizeAndDownloadQueuesItWithItsNzb() = runBlocking<Unit> {
        season()
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
        val copy = container.downloadStorage.packs.find(ReleaseKey.of(IMPORTED, request.nzbId)) ?: throw AssertionError("no copy of the NZB kept")
        assertArrayEquals(nzb, copy.source.nzb)
        assertEquals("its host asked for once", listOf(ScheduleCause.Download), scheduled.toList())
    }

    @Test
    fun aFileThatWontFitSaysWhatItNeedsExactlyWithStorageSettingsAndNoDownloadWholeAtTwiceTheFontSize() = runBlocking<Unit> {
        season()
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
        season()
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
     * L454: each video's row ends in a 48 dp button with the Download arrow, a button for TalkBack read
     * "Download <name>" with no state yet, which opens the file's Download sheet; the .nfo, no video, has none, and says so
     * after its size.
     */
    @Test
    fun eachVideosRowEndsInA48DpDownloadButtonAndTheNfoHasNone() = runBlocking<Unit> {
        val pack = pack(listOf(SMALL, SMALL + 1_000))
        open(pack)
        waitFor(hasText(NFO), 10_000)
        val px = 48 * context.resources.displayMetrics.density
        pack.files.forEach { file ->
            val button = compose.onAllNodes(hasContentDescription(label(file.name))).fetchSemanticsNodes().singleOrNull()
                ?: throw AssertionError("no Download button on ${file.name}'s row")
            assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
            assertNull("no state before a download", button.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals("48 dp wide", px, button.boundsInRoot.width, 1f)
            assertEquals("48 dp tall", px, button.boundsInRoot.height, 1f)
            assertEquals(DownloadMark.Download, markOf(file.name))
            assertTrue("its size, nothing else", exists(hasText(file.name) and hasText(formatSize(file.data.size.toLong()))))
        }
        assertFalse("no button on the .nfo", exists(hasContentDescription(label(NFO))))
        assertTrue(exists(hasText(NFO) and hasText("2 KB · not a video")))

        compose.onNode(hasContentDescription(label(pack.files[1].name))).performClick()
        waitFor(hasText(DownloadSheetModel.THIS_FILE))
        assertTrue(exists(hasText("Quiet Meridian · S01E02")))
        compose.onNode(hasText("Cancel") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { closes > 0 }
        assertTrue("nothing queued by the sheet's Cancel", mine().isEmpty())
    }

    /**
     * L454, L455, L423, L458: Download from the row queues the file (the button muted, "Queued for download"); Stored data
     * cleared meanwhile loses nothing, as its NZB is kept in `.packs/`; the app's runner then downloads it from that copy
     * alone, the button turning into the ring as the file is written and then the tick, "Downloaded" after the size; the
     * file is whole and the copy goes (C11). A tap on the row then plays that file: the download's launch, its watch the
     * release's, the player never asked to stream the NZB.
     */
    @Test
    fun aFileDownloadedFromItsRowRingsThenTicksWithStoredDataClearedAndThenPlaysFromItsFile() = runBlocking<Unit> {
        // The runner walks the app's whole queue: anything left to finish would be taken up first, and changed.
        val left = container.data.downloads.all().first().filter { it.state in DownloadRunner.GOING_ON }.map { it.id }
        assertTrue("downloads an earlier run left to finish, ${left.size}: $left", left.isEmpty())
        val pack = pack(listOf(SMALL), film = DownloadFixtures.media(MEDIA))
        val file = pack.files[1]
        open(pack)
        compose.onNode(hasContentDescription(label(file.name))).performClick()
        tap(DownloadSheetModel.TITLE)
        waitClosed()
        val row = waitFor("the row queued") { runBlocking { mine() }.singleOrNull() }
        assertEquals(DownloadState.Queued, row.state)
        assertEquals(WatchKey.release(pack.id, 1), row.watchKey)
        assertEquals(listOf(ScheduleCause.Download), scheduled.toList())
        compose.waitUntil(10_000) { markOf(file.name) == DownloadMark.Queued }
        assertTrue(exists(hasText(file.name) and hasText("${formatSize(file.data.size.toLong())} · ${TitleDownloads.QUEUED}")))
        assertEquals(TitleDownloads.QUEUED, buttonOf(file.name).config.getOrNull(SemanticsProperties.StateDescription))

        // Stored data cleared: the NZB store loses Import's copy, never the download's.
        container.store.clearStored()
        assertNull("the NZB store's file", container.store.stored(pack.id))
        val key = ReleaseKey.of(IMPORTED, pack.id)
        assertTrue("the download's own copy", container.downloadStorage.packs.find(key) != null)

        // Slow enough for the ring to show: the film in about five seconds.
        throttle.parentFile?.mkdirs()
        throttle.writeText("$THROTTLE")
        walk = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope -> scope.launch { container.downloads.run(DownloadHost.Job) } }
        compose.waitUntil(60_000) { (markOf(file.name) as? DownloadMark.Progress)?.held == false }
        compose.waitUntil(120_000) { markOf(file.name) == DownloadMark.Done }
        assertTrue(exists(hasText(file.name) and hasText("${formatSize(file.data.size.toLong())} · ${TitleDownloads.DOWNLOADED}")))
        val done = container.data.downloads.get(row.id) ?: throw AssertionError("the row gone")
        assertEquals(DownloadState.Done, done.state)
        assertEquals(DownloadFixtures.sha256(file.data), DownloadFixtures.sha256(container.downloadStorage.file(done) ?: throw AssertionError("no file")))
        waitFor("the NZB's copy gone with its download done", 10_000) { Unit.takeIf { runBlocking { container.downloadStorage.packs.find(key) } == null } }
        assertTrue("never grabbed again: Import's own release alone tried", done.tried.all { it == key })

        // Its row's tap: the file, by the hook, not the NZB's stream.
        val started = playerStarts()
        waitFor("the download among the finished ones") { container.downloads.done.value[done.watchKey] }
        compose.onNode(hasText(file.name) and hasClickAction()).performClick()
        val launch = withTimeout(LAUNCH_MILLIS) { container.playback.launches.filter { it.watchKey == done.watchKey }.first() }
        assertEquals(PlayUris.download(done.id), launch.uri)
        assertEquals(WatchKind.Release, launch.watch.kind)
        assertFalse("its progress saved, as a release's", launch.imported)
        assertTrue("the NZB never streamed", started.isEmpty())
    }

    /**
     * L454, L469, C7: started, the button opens its download's menu by state: queued, Remove download and Open Downloads;
     * written, Pause download first; finished, Remove download; failed, Download again first. Open Downloads opens the page.
     * Download again on a failed file whose NZB's copy is gone queues it again with the NZB Import holds: the same row,
     * queued, its copy written again, its host asked for. Remove's Undo bar brings a removed one back.
     */
    @Test
    fun theButtonsMenuIsItsDownloadsByStateAndDownloadAgainWritesTheCopyAgain() = runBlocking<Unit> {
        val pack = pack(listOf(SMALL, SMALL + 1_000, SMALL + 2_000, SMALL + 3_000))
        val (queued, written, finished, failed) = pack.files.mapIndexed { i, file ->
            val state = listOf(DownloadState.Queued, DownloadState.Running, DownloadState.Done, DownloadState.Failed)[i]
            seeds.seed(imported(pack, i, file, state))
        }
        open(pack)
        mapOf(
            queued to listOf(DownloadEntry.Remove, DownloadEntry.OpenDownloads),
            written to listOf(DownloadEntry.Pause, DownloadEntry.Remove, DownloadEntry.OpenDownloads),
            finished to listOf(DownloadEntry.Remove, DownloadEntry.OpenDownloads),
            failed to listOf(DownloadEntry.DownloadAgain, DownloadEntry.Remove, DownloadEntry.OpenDownloads),
        ).forEach { (row, entries) ->
            compose.waitUntil(10_000) { markOf(row.title) == TitleDownloads.mark(TitleDownload.of(DownloadRunner.Item(row, row.safeOffset, null))) }
            compose.onNode(hasContentDescription(label(row.title))).performClick()
            compose.waitUntil(5_000) { exists(hasText(entries.first().label) and inMenu) }
            assertEquals("${row.state}'s menu", entries.map { it.label }, menuItems())
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !exists(inMenu) }
        }
        assertTrue(exists(hasText(pack.files[1].name) and hasText("${formatSize(written.indexerSize)} · Downloading · 50%")))
        assertTrue(exists(hasText(pack.files[3].name) and hasText("${formatSize(failed.indexerSize)} · ${TitleDownloads.FAILED}")))

        // Open Downloads: the page.
        menu(queued.title, DownloadEntry.OpenDownloads)
        assertEquals(1, downloadsOpened)

        // Download again, with no copy of the NZB kept: written again from what Import holds, the row put back.
        val key = ReleaseKey.of(IMPORTED, pack.id)
        assertNull(container.downloadStorage.packs.find(key))
        menu(failed.title, DownloadEntry.DownloadAgain)
        val back = waitFor("the failed one queued again") { runBlocking { container.data.downloads.get(failed.id) }?.takeIf { it.state == DownloadState.Queued } }
        assertEquals("one row for the file", 1, mine().count { it.watchKey == failed.watchKey })
        assertEquals(emptyList<String>(), back.tried)
        assertArrayEquals(pack.nzb, (container.downloadStorage.packs.find(key) ?: throw AssertionError("no copy written again")).source.nzb)
        waitFor("its host asked for") { Unit.takeIf { ScheduleCause.Download in scheduled } }

        // Remove, and its Undo.
        menu(finished.title, DownloadEntry.Remove)
        waitFor(hasText("Undo") and hasClickAction())
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        val brought = waitFor("the finished one back") { runBlocking { container.data.downloads.get(finished.id) }?.takeIf { it.state == DownloadState.Done } }
        assertEquals(finished.watchKey, brought.watchKey)
    }

    /**
     * L455: a copy of a disc plays as no single video, so none of its files has a Download button: not its stream, a video,
     * nor its sample. A Blu-ray's folder in a stored RAR5 set, as DiscCopyDeviceTest posts one, with a sample beside it.
     */
    @Test
    fun noFileOfACopyOfADiscHasADownloadButton() = runBlocking<Unit> {
        val salt = System.nanoTime()
        val disc = "Copper.Crown.2022.COMPLETE.BLURAY"
        val files = listOf(
            Packed("$disc/BDMV/index.bdmv", "INDX0200".toByteArray() + ByteArray(1_016)),
            Packed("$disc/BDMV/PLAYLIST/00800.mpls", "MPLS0200".toByteArray() + ByteArray(2_040)),
            Packed("$disc/BDMV/STREAM/00800.m2ts", Random(salt).nextBytes(SMALL)),
            Packed("$disc/Sample/copper.crown.sample.mkv", Random(salt + 1).nextBytes(SMALL / 2)),
        )
        val bytes = archive(disc, files, salt)
        open(Pack(bytes, PreparedNzb.idFor(bytes).also(nzbIds::add), files), title = disc, first = "00800.m2ts")
        waitFor(hasText("copper.crown.sample.mkv"))
        val buttons = compose.onAllNodes(SemanticsMatcher("a Download button") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Download ") }
        }).fetchSemanticsNodes()
        assertEquals("Download buttons on a copy of a disc", 0, buttons.size)
    }

    /** L454: at twice the font size the names wrap whole, and each button keeps its 48 dp at its row's end. */
    @Test
    fun atTwiceTheFontSizeTheNamesWrapWholeAndEachButtonKeeps48Dp() = runBlocking<Unit> {
        val pack = pack(listOf(SMALL, SMALL + 1_000))
        open(pack, fontScale = 2f)
        val width = context.resources.displayMetrics.widthPixels
        val px = 48 * context.resources.displayMetrics.density
        pack.files.forEach { file ->
            compose.assertWhole(file.name, width)
            val button = buttonOf(file.name).boundsInRoot
            assertEquals("48 dp wide", px, button.width, 1f)
            assertEquals("48 dp tall", px, button.height, 1f)
            val name = compose.onAllNodes(hasText(file.name), useUnmergedTree = true).fetchSemanticsNodes().first { it.textLayout() != null }.boundsInRoot
            assertTrue("the button at the row's end, after the name", button.left >= name.right - 1f && button.right <= width + 1f)
        }
    }

    // The sheet's cases' season pack, and the sheet.

    /** Quiet Meridian season 1 as one NZB of two episodes, each a split RAR4 set, prepared as Import prepares it with a password typed. */
    private suspend fun season() {
        val salt = System.nanoTime()
        val episodes = (1..2).map { e ->
            fixtures.post(Packing.Rar4, "Quiet.Meridian.S01E%02d.1080p.WEB-DL.H264".format(e), DownloadFixtures.seeded(16 * DownloadFixtures.MB, salt + e), salt = "$salt")
        }
        nzb = fixtures.local.nzb(*episodes.flatMap { it.posted }.toTypedArray())
        val outcome = engineSetup("the NZB prepared as Import prepares it") { container.preparer.prepare(nzb, NZB_TITLE, PASSWORD) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("the NZB didn't prepare: $outcome")
        val file = prepared.files.firstOrNull { it.name == E02 } ?: throw AssertionError("no $E02 in ${prepared.files.size} files")
        request = DownloadRequest.File.of(prepared, file, nzb) ?: throw AssertionError("no Download for $E02")
        nzbIds += request.nzbId
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
     * Wi-Fi or Ethernet on), the network (Wi-Fi, or mobile data where [metered]) and the host's schedule, recorded.
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
            schedule = { scheduled += ScheduleCause.Download },
        )

    // Import, its rows and their buttons.

    /** A file in an NZB's archive: its [name] and its [data]. */
    private class Packed(val name: String, val data: ByteArray)

    /** An NZB the case posted: its [nzb] bytes, its [id], and its videos ([files]) as packed. */
    private class Pack(val nzb: ByteArray, val id: String, val files: List<Packed>)

    /**
     * Quiet Meridian season 1 as one NZB of a stored RAR5 set, as a pack posts one: an episode of each of [sizes]' random
     * bytes, then where given the [film] as the last episode, then its .nfo, posted under ids no run used.
     */
    private fun pack(sizes: List<Int>, film: ByteArray? = null): Pack {
        val salt = System.nanoTime()
        val videos = (sizes.map { Random(salt + it).nextBytes(it) } + listOfNotNull(film)).mapIndexed { i, data -> Packed(name(i + 1), data) }
        val bytes = archive(NZB_TITLE, videos + Packed(NFO, Random(salt).nextBytes(2_000)), salt)
        return Pack(bytes, PreparedNzb.idFor(bytes).also(nzbIds::add), videos)
    }

    /**
     * [files] in a stored RAR5 set named [title], its volumes posted under ids no run used: the NZB an indexer would give. RAR5,
     * as the generator makes a set of more than one file in it alone.
     */
    private fun archive(title: String, files: List<Packed>, salt: Long): ByteArray {
        val volumes = ArchiveGenerator.generate(files.map { ArchiveFile(it.name, it.data) }, DownloadFixtures.VOLUME, ContainerType.RAR5).mapIndexed { i, volume ->
            fixtures.local.post("$title.part${i + 1}.rar", volume.data, DownloadFixtures.PART, "imp-$salt-$i")
        }
        return fixtures.local.nzb(*volumes.toTypedArray())
    }

    /**
     * Import with [pack] handed over as another app hands a .nzb over, at [fontScale], until its file [first] is listed:
     * its model's host schedule the test's seam, Open Downloads counted, and its sheet the app's over [deps].
     */
    private fun open(pack: Pack, fontScale: Float = 1f, title: String = NZB_TITLE, first: String = pack.files.first().name): ImportViewModel {
        val model = ImportViewModel(context.applicationContext as Application, schedule = { scheduled += it }).also { vm = it }
        val opened = container.nzbHandoff.put(Result.success(pack.nzb to title))
        compose.show(context, fontScale) {
            ImportScreen(opened, onBack = {}, onProviders = {}, onDownloads = { downloadsOpened++ }, vm = model) { request, onClose ->
                DownloadSheet(request, onClose = {
                    onClose()
                    closes++
                }, deps())
            }
        }
        waitFor(hasText(first), PREPARE_MILLIS)
        return model
    }

    /** [file], the [index]th of [pack]'s files, downloaded in [state] as Import's Download leaves one: its release kept once done. */
    private fun imported(pack: Pack, index: Int, file: Packed, state: DownloadState): Download {
        val id = Download.newId()
        val size = file.data.size.toLong()
        val made = state == DownloadState.Done || state == DownloadState.Running
        return Download(
            id = id, watchKey = WatchKey.release(pack.id, index), kind = DownloadKind.Release, title = file.name, subtitle = NZB_TITLE, state = state,
            position = -500L + index, candidates = listOf(DownloadCandidate(IMPORTED, pack.id, NZB_TITLE, size = size)),
            release = RememberedRelease(IMPORTED, pack.id, NZB_TITLE, null, index, pack.id, file.name, size).takeIf { state == DownloadState.Done },
            fileName = file.name.takeIf { made }, size = size.takeIf { made }, indexerSize = size,
            safeOffset = when (state) {
                DownloadState.Done -> size
                DownloadState.Running -> size / 2
                else -> 0
            },
            relativePath = "downloads/$id/v.mkv".takeIf { made }, pick = DownloadColumns.text(DownloadPick(index, file.name, size, pack.id)),
            reason = IMPORT_GONE.takeIf { state == DownloadState.Failed },
            createdAt = 1, completedAt = 2L.takeIf { state == DownloadState.Done }, updatedAt = System.currentTimeMillis(),
        )
    }

    /** "Download Quiet.Meridian.S01E02.1080p.WEB-DL.H264.mkv": [name]'s button for TalkBack. */
    private fun label(name: String) = "Download $name"

    private fun buttonOf(name: String): SemanticsNode =
        compose.onAllNodes(hasContentDescription(label(name))).fetchSemanticsNodes().singleOrNull() ?: throw AssertionError("no button for $name")

    /** The mark [name]'s button shows now; null without a button. */
    private fun markOf(name: String): DownloadMark? {
        val button = compose.onAllNodes(hasContentDescription(label(name))).fetchSemanticsNodes().singleOrNull() ?: return null
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(DownloadMarkLook), useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { button.boundsInRoot.contains(it.boundsInRoot.center) }?.config?.getOrNull(DownloadMarkLook)
    }

    /** [entry] of [name]'s button's menu, taken. */
    private fun menu(name: String, entry: DownloadEntry) {
        compose.onNode(hasContentDescription(label(name))).performClick()
        compose.waitUntil(5_000) { exists(hasText(entry.label) and inMenu) }
        compose.onNode(hasText(entry.label) and inMenu).performClick()
        compose.waitForIdle()
    }

    private val inMenu = hasClickAction() and hasAnyAncestor(isPopup())

    /** The open menu's items, in order. */
    private fun menuItems(): List<String> = compose.onAllNodes(inMenu).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    /** The player's starts, caught and never let through: what was asked of it. */
    private fun playerStarts(): List<Intent> {
        val started = CopyOnWriteArrayList<Intent>()
        monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.className == PlayerActivity::class.java.name) Instrumentation.ActivityResult(0, null).also { started += intent } else null
        }.also(instrumentation::addMonitor)
        return started
    }

    /** This case's rows: those of files of the NZBs it opened. */
    private suspend fun mine(): List<Download> =
        container.data.downloads.all().first().filter { row -> row.kind == DownloadKind.Release && DownloadColumns.pick(row)?.nzbId in nzbIds }

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
        const val NFO = "Quiet.Meridian.S01.nfo"

        /** The archive password typed on Import for the sheet's season pack. */
        const val PASSWORD = "typed-on-import"

        /** A row's file where its bytes needn't play: about a megabyte. */
        const val SMALL = 1_000_000

        /** The device tests' film with chapters, 0.7 MB: Resolve's probe reads it as a film. */
        const val MEDIA = "chapters.mkv"

        /** The writer's bytes a second while the ring is looked for: the film in about five seconds. */
        const val THROTTLE = 150_000

        const val PREPARE_MILLIS = 60_000L
        const val LAUNCH_MILLIS = 30_000L

        /** Quiet Meridian season 1's episode [n]'s file, as its NZB names it. */
        fun name(n: Int) = "Quiet.Meridian.S01E%02d.1080p.WEB-DL.H264.mkv".format(n)
    }
}
